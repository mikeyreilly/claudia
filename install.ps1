<#
.SYNOPSIS
Builds Claudia and its built-in code-lens MCP server in this checkout and
writes a claudia.cmd launcher that runs them in place.

.DESCRIPTION
Builds Claudia (target\claudia.jar) and code-lens
(code-lens\build\code-lens.exe) in this checkout, then writes a
claudia.cmd launcher that runs this checkout's build. Re-run it to update
after pulling changes, or after moving the checkout.

code-lens is optional: when it cannot be built (no clang/Windows SDK, or its
vendored sources cannot be downloaded) a WARNING is printed and Claudia is
still installed, just without the code-lens integration.

Requirements: a JDK 25 or newer (JAVA_HOME or java on PATH) and Maven.
code-lens additionally needs LLVM clang with a Windows SDK, Git for Windows,
curl.exe, and tar.exe.

Run it through install.cmd, or with:
    powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1

.PARAMETER BinDir
Directory for the claudia.cmd launcher (default: %USERPROFILE%\.local\bin).

.PARAMETER Native
Build the GraalVM native executable and launch it instead of the jar.

.PARAMETER RunTests
Run the Claudia test suite during the Maven build.
#>
[CmdletBinding()]
param(
    [string]$BinDir = (Join-Path $env:USERPROFILE '.local\bin'),
    [switch]$Native,
    [switch]$RunTests
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$CodeLensDir = Join-Path $Root 'code-lens'
$CodeLensExe = Join-Path $CodeLensDir 'build\code-lens.exe'
$script:Warnings = New-Object 'System.Collections.Generic.List[string]'

function Say([string]$Message) { Write-Host "==> $Message" }

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Warn([string]$Message) {
    Write-Warning $Message
    $script:Warnings.Add("WARNING: $Message")
}

# Runs a native program with its output on the console and returns its exit
# code. Windows PowerShell turns native stderr into errors under 'Stop'.
function Invoke-Native([string]$Program, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Program @Arguments | Out-Host
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Find-Program([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { return $command.Path }
    }
    return $null
}

function Get-JavaVersionText([string]$Java) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Java
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $text = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    return $text
}

# ----------------------------------------------------------------- prerequisites

$Java = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $Java = $candidate }
}
if (-not $Java) { $Java = Find-Program @('java.exe') }
if (-not $Java) {
    Fail 'Java was not found. Install a JDK 25 or newer, then set JAVA_HOME or put java on PATH.'
}
$versionText = Get-JavaVersionText $Java
if ($versionText -notmatch 'version "(\d+)[^"]*"') { Fail "Could not determine the version of $Java." }
if ([int]$Matches[1] -lt 25) {
    Fail "Claudia needs a JDK 25 or newer, but $Java reports: $(($versionText -split "`n")[0].Trim())"
}

$Maven = Find-Program @('mvn.cmd', 'mvn.bat', 'mvn.exe')
if (-not $Maven) { Fail 'Maven (mvn) was not found on PATH. Install Apache Maven 3.9 or newer.' }

if ($Native) {
    $nativeImage = Join-Path (Split-Path -Parent $Java) 'native-image.cmd'
    if (-not (Test-Path -LiteralPath $nativeImage) -and -not (Find-Program @('native-image.cmd', 'native-image.exe'))) {
        Fail '-Native needs GraalVM native-image; use a GraalVM JDK 25 as JAVA_HOME.'
    }
    Warn 'native executables do not support interactive mode on Windows; consider installing without -Native'
}

# --------------------------------------------------------------------- code-lens

function Skip-CodeLens([string]$Reason) {
    Warn "code-lens was not built: $Reason"
    if (Test-Path -LiteralPath $CodeLensExe -PathType Leaf) {
        Warn 'a code-lens.exe from an earlier build remains in code-lens\build and will still be used'
    } else {
        Warn 'Claudia will be installed without its built-in code-lens MCP server'
    }
}

# True when every pinned dependency in vendor\deps.lock is already in place, so
# a rebuild needs no network access.
function Test-CodeLensVendorCurrent([string]$Git) {
    foreach ($line in Get-Content -LiteralPath (Join-Path $CodeLensDir 'vendor\deps.lock')) {
        $fields = @($line.Trim() -split '\s+')
        if ($fields.Count -lt 3 -or $fields[0].StartsWith('#')) { continue }
        $name = $fields[0]
        $ref = $fields[2]
        if ($name -eq 'sqlite-amalgamation') {
            $stamp = Join-Path $CodeLensDir 'vendor\sqlite\.sha256'
            if (-not (Test-Path -LiteralPath $stamp -PathType Leaf)) { return $false }
            if ((Get-Content -LiteralPath $stamp -TotalCount 1).Trim() -ne $ref) { return $false }
        } else {
            $directory = Join-Path $CodeLensDir "vendor\$name"
            if (-not (Test-Path -LiteralPath (Join-Path $directory '.git'))) { return $false }
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { $head = & $Git -C $directory rev-parse HEAD 2>$null } finally { $ErrorActionPreference = $previous }
            if ("$head".Trim() -ne $ref) { return $false }
        }
    }
    return $true
}

function Build-CodeLens {
    $missing = @()
    foreach ($tool in @('clang.exe', 'git.exe', 'curl.exe', 'tar.exe')) {
        if (-not (Find-Program @($tool))) { $missing += $tool }
    }
    if ($missing.Count -gt 0) {
        Skip-CodeLens ("missing required tools: $($missing -join ', ') " +
            '(install LLVM clang and Git for Windows, and put them on PATH)')
        return
    }

    if (Test-CodeLensVendorCurrent (Find-Program @('git.exe'))) {
        Say 'code-lens vendored dependencies are up to date'
    } else {
        Say 'Downloading code-lens vendored dependencies'
        if ((Invoke-Native (Join-Path $CodeLensDir 'scripts\vendor-deps.bat') @()) -ne 0) {
            Skip-CodeLens ('its vendored dependencies could not be downloaded ' +
                '(see the output above; check access to github.com and sqlite.org)')
            return
        }
    }

    Say 'Building code-lens with clang'
    if ((Invoke-Native (Join-Path $CodeLensDir 'build.bat') @('release')) -ne 0) {
        Skip-CodeLens ('the build failed (see the output above). Clang needs a Windows SDK and MSVC runtime, ' +
            'for example from Visual Studio Build Tools; a running Claudia session also keeps ' +
            'code-lens\build\code-lens.exe locked, so close it and re-run')
        return
    }
    if (-not (Test-Path -LiteralPath $CodeLensExe -PathType Leaf)) {
        Skip-CodeLens "the build did not produce $CodeLensExe"
        return
    }
    if ($RunTests) { Say 'The code-lens test suite is not available in the native Windows build; skipping it' }
}

Build-CodeLens

# ------------------------------------------------------------------- Claudia

$mavenArguments = @('-B', '-f', (Join-Path $Root 'pom.xml'))
if (-not $RunTests) { $mavenArguments += '-DskipTests' }
if ($Native) { $mavenArguments += '-Pnative' }
$mavenArguments += 'package'
Say "Building Claudia with Maven (mvn $($mavenArguments -join ' '))"
if ((Invoke-Native $Maven $mavenArguments) -ne 0) {
    Fail ('The Maven build failed; see the output above. If target\claudia.jar is in use by a running ' +
        'Claudia session, close it and re-run.')
}

function Format-BatchValue([string]$Value) { return $Value.Replace('%', '%%') }

if ($Native) {
    $Program = Join-Path $Root 'target\claudia.exe'
    if (-not (Test-Path -LiteralPath $Program -PathType Leaf)) { Fail "The native build did not produce $Program." }
    # One line: cmd.exe rereads a batch file after each command, so a launcher
    # replaced by a later install must not have anything left to read.
    $launch = "`"$(Format-BatchValue $Program)`" %* & exit /b"
} else {
    $Program = Join-Path $Root 'target\claudia.jar'
    if (-not (Test-Path -LiteralPath $Program -PathType Leaf)) { Fail "The Maven build did not produce $Program." }
    $launch = "set `"CLAUDIA_JAVA=$(Format-BatchValue $Java)`"`r`n" +
        "if not exist `"%CLAUDIA_JAVA%`" set `"CLAUDIA_JAVA=java`"`r`n" +
        "`"%CLAUDIA_JAVA%`" -jar `"$(Format-BatchValue $Program)`" %* & exit /b"
}

# ---------------------------------------------------------------------- launcher

New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
$BinDir = (Resolve-Path -LiteralPath $BinDir).ProviderPath
$Launcher = Join-Path $BinDir 'claudia.cmd'
$content = "@echo off`r`n" +
    "rem Generated by install.ps1 in the Claudia checkout; re-run it after moving the checkout.`r`n" +
    "rem Runs that checkout's build, which finds its code-lens build.`r`n" +
    "setlocal`r`n" +
    "$launch`r`n"
# cmd.exe reads batch files in the console's OEM code page.
try {
    $encoding = [System.Text.Encoding]::GetEncoding([System.Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
} catch {
    $encoding = [System.Text.Encoding]::Default
}

$current = $null
if (Test-Path -LiteralPath $Launcher -PathType Leaf) {
    $current = [System.IO.File]::ReadAllText($Launcher, $encoding)
}
if ($current -ceq $content) {
    Say "Launcher $Launcher is up to date"
} else {
    $temporary = "$Launcher.tmp"
    try {
        [System.IO.File]::WriteAllText($temporary, $content, $encoding)
        Move-Item -LiteralPath $temporary -Destination $Launcher -Force
    } catch {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        Fail "Could not write ${Launcher}: $($_.Exception.Message)"
    }
    Say "Wrote launcher $Launcher"
}

# ----------------------------------------------------------------------- summary

Write-Host ''
Say 'Claudia is installed'
Write-Host "    launcher:  $Launcher"
Write-Host "    runs:      $Program"
if (Test-Path -LiteralPath $CodeLensExe -PathType Leaf) {
    Write-Host "    code-lens: $CodeLensExe (built-in MCP server)"
} else {
    Write-Host '    code-lens: not available'
}
if ($script:Warnings.Count -gt 0) {
    Write-Host ''
    foreach ($warning in $script:Warnings) { Write-Host $warning -ForegroundColor Yellow }
}
$pathEntries = @($env:Path -split ';' | Where-Object { $_ } | ForEach-Object { $_.TrimEnd('\') })
if ($pathEntries -notcontains $BinDir.TrimEnd('\')) {
    Write-Host ''
    Write-Host "NOTE: $BinDir is not on your PATH. Add it under 'Edit environment variables for your account',"
    Write-Host '      or run this in PowerShell and open a new terminal:'
    Write-Host ("          [Environment]::SetEnvironmentVariable('Path', " +
        "[Environment]::GetEnvironmentVariable('Path', 'User') + ';$($BinDir.Replace("'", "''"))', 'User')")
}
exit 0
