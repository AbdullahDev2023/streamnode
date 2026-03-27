@echo off
setlocal enabledelayedexpansion
color 0A
title StreamNode — GitHub Push Setup

echo.
echo  ==========================================
echo   StreamNode Server — GitHub Setup Script
echo  ==========================================
echo.

:: ── Check git is installed ───────────────────────────────────────────────────
git --version >nul 2>&1
if errorlevel 1 (
    echo  [ERROR] Git is not installed or not in PATH.
    echo  Download from: https://git-scm.com/download/win
    pause & exit /b 1
)

:: ── Ask for GitHub username and repo name ────────────────────────────────────
echo  You need a GitHub account. If you don't have one, create one at:
echo  https://github.com/signup
echo.
set /p GITHUB_USER= Enter your GitHub username: 
set /p REPO_NAME= Enter repo name (e.g. streamnode-server): 

set REPO_URL=https://github.com/%GITHUB_USER%/%REPO_NAME%.git

echo.
echo  BEFORE CONTINUING — Create the repo on GitHub NOW:
echo.
echo    1. Open: https://github.com/new
echo    2. Repository name: %REPO_NAME%
echo    3. Set to: Public  (required for Render free tier)
echo    4. Do NOT check "Add README" or any other file
echo    5. Click "Create repository"
echo.
pause

:: ── Navigate to server folder ────────────────────────────────────────────────
cd /d "%~dp0"
echo  Working directory: %CD%
echo.

:: ── Git setup ────────────────────────────────────────────────────────────────
echo  [1/5] Initialising git...
git init
if errorlevel 1 ( echo [ERROR] git init failed & pause & exit /b 1 )

echo  [2/5] Setting branch to main...
git checkout -b main 2>nul || git branch -M main

echo  [3/5] Staging all files...
git add .
git status

echo.
echo  [4/5] Creating first commit...
git commit -m "StreamNode Server v5.0 - initial deploy"
if errorlevel 1 ( echo [ERROR] commit failed & pause & exit /b 1 )

echo  [5/5] Pushing to GitHub...
git remote remove origin 2>nul
git remote add origin %REPO_URL%
git push -u origin main
if errorlevel 1 (
    echo.
    echo  [ERROR] Push failed. Common fixes:
    echo.
    echo   A) GitHub login: run this and try again:
    echo      git config --global credential.helper manager
    echo.
    echo   B) Repo does not exist yet — create it first at:
    echo      https://github.com/new
    echo.
    pause & exit /b 1
)

echo.
echo  ==========================================
echo   SUCCESS! Server is on GitHub.
echo  ==========================================
echo.
echo  Your repo URL:
echo  https://github.com/%GITHUB_USER%/%REPO_NAME%
echo.
echo  NEXT STEP — Deploy on Render.com:
echo   1. Go to https://render.com and sign up FREE
echo   2. New + → Web Service → Connect GitHub
echo   3. Select repo: %REPO_NAME%
echo   4. Render reads render.yaml automatically
echo   5. Add env var: FIREBASE_DB_SECRET = your_value
echo   6. Click "Create Web Service"
echo.
echo  After deploy you get a URL like:
echo  https://streamnode-server.onrender.com
echo.
echo  Then set CNAME in Hostinger DNS:
echo   Name  : streamnode
echo   Value : streamnode-server.onrender.com
echo.
pause
