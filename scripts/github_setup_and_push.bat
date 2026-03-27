@echo off
setlocal enabledelayedexpansion
title GitHub — Full Setup and Push
color 0E

echo.
echo  ╔════════════════════════════════════════════════════╗
echo  ║   StreamNode — GitHub Setup and Push (Master)     ║
echo  ╚════════════════════════════════════════════════════╝
echo.
echo  This script will:
echo    1. Create the GitHub repository  (github_create_repo.bat)
echo    2. Commit and push the project   (github_push.bat)
echo.

set "SCRIPTS=%~dp0"

:: ── Collect shared credentials once ──────────────────────────────────────────
set "GH_TOKEN=%GITHUB_TOKEN%"
set "GH_USER=%GITHUB_USERNAME%"
set "REPO_NAME=streamnode"

if "%GH_TOKEN%"=="" (
    set /p GH_TOKEN="  GitHub Personal Access Token (repo scope): "
)
if "%GH_USER%"=="" (
    set /p GH_USER="  GitHub username: "
)
if "%REPO_NAME%"=="" (
    set /p REPO_NAME="  Repository name [streamnode]: "
    if "!REPO_NAME!"=="" set "REPO_NAME=streamnode"
)

:: Export so child scripts inherit them
set "GITHUB_TOKEN=%GH_TOKEN%"
set "GITHUB_USERNAME=%GH_USER%"

echo.
echo  ── Step 1 / 2 : Create Repository ──────────────────
call "%SCRIPTS%github_create_repo.bat"
if %errorlevel% neq 0 (
    echo  [ERROR] github_create_repo.bat failed. Aborting.
    pause & exit /b 1
)

echo.
echo  ── Step 2 / 2 : Commit and Push ─────────────────────
call "%SCRIPTS%github_push.bat"
if %errorlevel% neq 0 (
    echo  [ERROR] github_push.bat failed.
    pause & exit /b 1
)

echo.
echo  ════════════════════════════════════════════════════
echo   ALL DONE!  https://github.com/%GH_USER%/%REPO_NAME%
echo  ════════════════════════════════════════════════════
echo.
endlocal
pause
