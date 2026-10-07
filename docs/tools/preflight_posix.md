# `preflight_posix`

Checks a Windows MSYS2 or Git Bash installation before a native build. It only
reads and reports; it never installs anything.

Defined in [`LocalTools.preflightPosix()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
implemented by [`PosixPreflight`](../../src/main/java/com/quaxt/claudia/shell/PosixPreflight.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `layer` | string | required | `msys2` or `git-bash`. |
| `installation_root` | string | detected | Windows path to the installation's root folder. |
| `environment` | map | none | Environment variables to set for discovery and checks. |
| `unset_environment` | string[] | none | Environment variable names to remove. |
| `inherit_environment` | boolean | `true` | Start from Claudia's environment. |

The environment parameters work the same way as in
[`run_process`](run_process.md), so the check sees the same environment as the
build will.

## Report

- `installation_root`, `windows_path` and `posix_path` (each `PATH` entry
  converted to a POSIX path).
- `tools`: where `bash`, `make`, `sh`, `find`, `zip`, `unzip`, `mktemp` and
  `cmd.exe` were found.
  - `missing_tools` lists the ones that weren't found.
  - `mixed_tools` lists POSIX tools found outside the selected installation.
- `compiler` and `compiler_version`: `$CC` if set, otherwise the first of
  `gcc`, `clang` and `cl.exe` found.
- For MSYS2 only, `missing_packages`: the `pacman` package that supplies each
  missing tool.
- Whether the temp directory (`TMPDIR`) is writable.
- Visual Studio toolsets found by `vswhere`.

On any OS other than Windows, the tool returns an error.

## UI summary

`Checking <layer> POSIX environment`
