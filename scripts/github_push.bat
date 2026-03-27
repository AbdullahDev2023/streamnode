@echo off
setlocal enabledelayedexpansion
title GitHub — Commit and Push
color 0A

echo.
echo  =====================================================
echo    StreamNode — Commit and Push to GitHub
echo  =====================================================
echo.

:: ROOT of the project (one level up from scripts\)
set "ROOT=%~dp0.."
set "GH_TOKEN=%GITHUB_TOKEN%"
set "GH_USER=%GITHUB_USERNAME%"
set "REPO_NAME=streamnode"

:: ── Prompt for missing credentials ───────────────────────────────────────────
if "%GH_TOKEN%"=="" (
    set /p GH_TOKEN="  GitHub Personal Access Token (repo scope): "
)
if "%GH_USER%"=="" (
    set /p GH_USER="  GitHub username: "
)

:: ── Enter project root ────────────────────────────────────────────────────────
pushd "%ROOT%"

:: ── Check git is available ───────────────────────────────────────────────────
where git >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] git not found in PATH.
    pause & exit /b 1
)

:: ── Ensure we are on 'main' branch ──────────────────────────────────────────
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%b"
if "%BRANCH%"=="" set "BRANCH=main"
echo  [Info] Current branch: %BRANCH%

if not "%BRANCH%"=="main" (
    echo  [Info] Switching to 'main' branch...
    git checkout -b main 2>nul || git checkout main
)

:: ── Stage all files (including server folder) ───────────────────────────────
echo.
echo  [1/4] Staging all changes...
git add -A
git add server/ 2>nul
git status --short

:: ── Commit (if there are changes) ────────────────────────────────────────────
echo.
echo  [2/4] Committing...
set /p COMMIT_MSG="  Commit message [chore: update project]: "
if "%COMMIT_MSG%"=="" set "COMMIT_MSG=chore: update project"

git diff --cached --quiet
if %errorlevel% equ 0 (
    echo  [Info] Nothing to commit — working tree is clean.
    goto :PUSH
)

git commit -m "%COMMIT_MSG%"
if %errorlevel% neq 0 (
    echo  [ERROR] Commit failed.
    popd & pause & exit /b 1
)
echo  [OK]  Committed: %COMMIT_MSG%

:PUSH

:: ── Ensure remote uses token-authenticated HTTPS URL ─────────────────────────
echo  [3/4] Updating remote with credentials...
git remote set-url origin "https://%GH_USER%:%GH_TOKEN%@github.com/%GH_USER%/%REPO_NAME%.git"

:: ── Push to GitHub ────────────────────────────────────────────────────────────
echo.
echo  [4/4] Pushing to GitHub (branch: main)...
git push -u origin main
if %errorlevel% neq 0 (
    echo.
    echo  [ERROR] Push failed. Common causes:
    echo         - Token expired or lacks 'repo' scope
    echo         - Repository not yet created (run github_create_repo.bat first)
    echo         - Network issue
    popd & pause & exit /b 1
)

echo.
echo  ─────────────────────────────────────────────────────
echo   [DONE] Project pushed successfully!
echo   View at: https://github.com/%GH_USER%/%REPO_NAME%
echo  ─────────────────────────────────────────────────────

:: ── Strip credentials from remote (security cleanup) ─────────────────────────
git remote set-url origin "https://github.com/%GH_USER%/%REPO_NAME%.git"
echo  [OK]  Credentials stripped from remote URL.

popd
echo.
endlocal
pause
