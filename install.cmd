@echo off
rem Builds codingagent and code-lens in this checkout and writes a codingagent.cmd
rem launcher. Options are passed to install.ps1: -BinDir DIR, -Native, -RunTests.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %ERRORLEVEL%
