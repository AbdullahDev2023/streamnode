@echo off
setlocal enabledelayedexpansion
title GitHub — Create Repository
color 0B

echo.
echo  =====================================================
echo    StreamNode — Create GitHub Repository
echo  =====================================================
echo.

:: ── Configuration ─────────────────────────────────────────────────────────────
:: Set these variables before running, or they will be prompted interactively.
set "GH_TOKEN=%GITHUB_TOKEN%"
set "GH_USER=%GITHUB_USERNAME%"
set "REPO_NAME=streamnode"
set "REPO_DESC=Stream Android microphone, screen and cameras live to any browser"
set "REPO_PRIVATE=false"

:: ROOT of the project
set "ROOT=%~dp0.."

:: ── Prompt for missing values ─────────────────────────────────────────────────
if "%GH_TOKEN%"=="" (
    set /p GH_TOKEN="  Enter your GitHub Personal Access Token (repo scope): "
)
if "%GH_USER%"=="" (
    set /p GH_USER="  Enter your GitHub username: "
)
if "%REPO_NAME%"=="" (
    set /p REPO_NAME="  Enter repository name [StreamNode]: "
    if "!REPO_NAME!"=="" set "REPO_NAME=streamnode"
)

echo.
echo  [Config] User       : %GH_USER%
echo  [Config] Repo       : %REPO_NAME%
echo  [Config] Visibility : %REPO_PRIVATE% (false = public)
echo.

:: ── Check for curl ───────────────────────────────────────────────────────────
where curl >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] curl is not installed or not in PATH.
    echo         Windows 10 v1803+ ships with curl. Please update Windows or
    echo         install curl from https://curl.se/windows/
    pause & exit /b 1
)

:: ── Create repository via GitHub REST API ────────────────────────────────────
echo  [1/3] Creating repository %GH_USER%/%REPO_NAME% on GitHub...

curl -s -o "%TEMP%\gh_create_response.json" -w "%%{http_code}" ^
  -X POST ^
  -H "Authorization: token %GH_TOKEN%" ^
  -H "Accept: application/vnd.github+json" ^
  -H "Content-Type: application/json" ^
  "https://api.github.com/user/repos" ^
  -d "{\"name\":\"%REPO_NAME%\",\"description\":\"%REPO_DESC%\",\"private\":%REPO_PRIVATE%,\"auto_init\":false}" ^
  > "%TEMP%\gh_http_code.txt" 2>&1

set /p HTTP_CODE=<"%TEMP%\gh_http_code.txt"

echo  [Info] HTTP status: %HTTP_CODE%

if "%HTTP_CODE%"=="201" (
    echo  [OK]  Repository created successfully!
    set "REPO_URL=https://github.com/%GH_USER%/%REPO_NAME%.git"
    set "REPO_SSH=git@github.com:%GH_USER%/%REPO_NAME%.git"
    goto :SET_REMOTE
)
if "%HTTP_CODE%"=="422" (
    echo  [WARN] Repository already exists on GitHub. Proceeding to set remote.
    set "REPO_URL=https://github.com/%GH_USER%/%REPO_NAME%.git"
    set "REPO_SSH=git@github.com:%GH_USER%/%REPO_NAME%.git"
    goto :SET_REMOTE
)

echo  [ERROR] Unexpected HTTP %HTTP_CODE%. Check your token/username.
type "%TEMP%\gh_create_response.json"
echo.
pause & exit /b 1

:SET_REMOTE
:: ── Update git remote ─────────────────────────────────────────────────────────
echo.
echo  [2/3] Configuring git remote...
pushd "%ROOT%"

:: Check if remote 'origin' already exists
git remote get-url origin >nul 2>&1
if %errorlevel% equ 0 (
    echo  [Info] Remote 'origin' exists. Updating URL...
    git remote set-url origin "%REPO_URL%"
) else (
    echo  [Info] Adding remote 'origin'...
    git remote add origin "%REPO_URL%"
)

echo  [OK]  Remote 'origin' -> %REPO_URL%

:: ── Save token-authenticated URL for HTTPS pushes ────────────────────────────
echo  [3/3] Storing credentials in remote URL for this session...
git remote set-url origin "https://%GH_USER%:%GH_TOKEN%@github.com/%GH_USER%/%REPO_NAME%.git"
echo  [OK]  Credentials embedded in remote URL (session only).

popd

echo.
echo  ─────────────────────────────────────────────────────
echo   Repository ready!
echo   HTTPS : https://github.com/%GH_USER%/%REPO_NAME%
echo   SSH   : %REPO_SSH%
echo  ─────────────────────────────────────────────────────
echo.
echo  Next step: Run scripts\github_push.bat to commit and push.
echo.
endlocal
pause
