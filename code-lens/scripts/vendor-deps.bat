@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "LOCK_FILE=%ROOT%\vendor\deps.lock"
set "VENDOR_DIR=%ROOT%\vendor"

if not exist "%LOCK_FILE%" (
  echo code-lens: dependency lock file not found: "%LOCK_FILE%" 1>&2
  exit /b 1
)
where git.exe >nul 2>nul || (echo code-lens: git.exe is required 1>&2 & exit /b 1)
where curl.exe >nul 2>nul || (echo code-lens: curl.exe is required 1>&2 & exit /b 1)
where powershell.exe >nul 2>nul || (echo code-lens: PowerShell is required 1>&2 & exit /b 1)
where tar.exe >nul 2>nul || (echo code-lens: tar.exe is required to extract zip archives 1>&2 & exit /b 1)
if not exist "%VENDOR_DIR%" mkdir "%VENDOR_DIR%" || exit /b 1

for /f "usebackq eol=# tokens=1,2,3,4" %%A in ("%LOCK_FILE%") do (
  call :process_dep "%%A" "%%B" "%%C" "%%D"
  if errorlevel 1 exit /b 1
)
exit /b 0

:process_dep
set "DEP_NAME=%~1"
set "DEP_URL=%~2"
set "DEP_REF=%~3"
set "DEP_LICENSE=%~4"
if not defined DEP_NAME exit /b 0
if not defined DEP_URL (echo code-lens: invalid dependency line for %DEP_NAME% 1>&2 & exit /b 1)
if not defined DEP_REF (echo code-lens: invalid dependency line for %DEP_NAME% 1>&2 & exit /b 1)
if not defined DEP_LICENSE (echo code-lens: invalid dependency line for %DEP_NAME% 1>&2 & exit /b 1)
if "%DEP_NAME%"=="sqlite-amalgamation" goto download_sqlite

set "DEP_DEST=%VENDOR_DIR%\%DEP_NAME%"
if exist "%DEP_DEST%\.git" (
  echo %DEP_NAME% already exists; verifying pinned ref
) else (
  if exist "%DEP_DEST%" (
    echo code-lens: %DEP_DEST% exists but is not a git checkout 1>&2
    exit /b 1
  )
  echo cloning %DEP_NAME%
  git clone --no-checkout --filter=blob:none "%DEP_URL%" "%DEP_DEST%" || exit /b 1
)
git -C "%DEP_DEST%" fetch --depth 1 origin "%DEP_REF%" || exit /b 1
git -C "%DEP_DEST%" checkout --detach "%DEP_REF%" || exit /b 1
exit /b 0

:download_sqlite
set "DEP_DEST=%VENDOR_DIR%\sqlite"
set "STAMP=%DEP_DEST%\.sha256"
if exist "%STAMP%" (
  set /p STAMP_HASH=<"%STAMP%"
  call :stamp_matches "%DEP_REF%"
  if not errorlevel 1 exit /b 0
)

echo downloading %DEP_NAME%
set "TMP_DIR=%TEMP%\code-lens-vendor-%RANDOM%-%RANDOM%"
set "ARCHIVE=%TMP_DIR%\archive.zip"
set "EXTRACT=%TMP_DIR%\extract"
mkdir "%TMP_DIR%" || exit /b 1
curl.exe -fsSL "%DEP_URL%" -o "%ARCHIVE%" || (rmdir /s /q "%TMP_DIR%" & exit /b 1)
set "CP_ARCHIVE=%ARCHIVE%"
set "ACTUAL_HASH="
for /f "usebackq delims=" %%H in (`powershell.exe -NoProfile -Command "$s=[Security.Cryptography.SHA256]::Create(); $f=[IO.File]::OpenRead($env:CP_ARCHIVE); try { ([BitConverter]::ToString($s.ComputeHash($f))).Replace('-','').ToLowerInvariant() } finally { $f.Dispose(); $s.Dispose() }"`) do set "ACTUAL_HASH=%%H"
if /i not "%ACTUAL_HASH%"=="%DEP_REF%" (
  echo %DEP_NAME% archive hash mismatch: expected %DEP_REF%, got %ACTUAL_HASH% 1>&2
  rmdir /s /q "%TMP_DIR%"
  exit /b 1
)
set "CP_EXTRACT=%EXTRACT%"
mkdir "%EXTRACT%" || (rmdir /s /q "%TMP_DIR%" & exit /b 1)
tar.exe -xf "%ARCHIVE%" -C "%EXTRACT%" || (rmdir /s /q "%TMP_DIR%" & exit /b 1)
if exist "%DEP_DEST%" rmdir /s /q "%DEP_DEST%"
mkdir "%DEP_DEST%" || (rmdir /s /q "%TMP_DIR%" & exit /b 1)
set "CP_DEST=%DEP_DEST%"
powershell.exe -NoProfile -Command "$items=@(Get-ChildItem -LiteralPath $env:CP_EXTRACT -Force); $source=$env:CP_EXTRACT; if($items.Count -eq 1 -and $items[0].PSIsContainer){$source=$items[0].FullName}; Get-ChildItem -LiteralPath $source -Force | Move-Item -Destination $env:CP_DEST -Force" || (rmdir /s /q "%TMP_DIR%" & exit /b 1)
>"%STAMP%" echo %DEP_REF%
rmdir /s /q "%TMP_DIR%"
exit /b 0

:stamp_matches
if /i "%STAMP_HASH%"=="%~1" (
  echo %DEP_NAME% already exists; pinned archive hash matches
  exit /b 0
)
exit /b 1
