@echo off
rem Windows wrapper for scripts/build-dist.sh (run from anywhere).
setlocal
where bash >nul 2>nul
if errorlevel 1 (
    echo Git Bash ^(bash^) was not found on PATH.
    echo Install Git for Windows, or run scripts/build-dist.sh from a bash shell.
    exit /b 1
)
bash "%~dp0build-dist.sh" %*
