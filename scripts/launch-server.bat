@echo off
setlocal enabledelayedexpansion
title StreamNode Server
color 0A

set "ROOT=%~dp0.."
set "SERVER_DIR=%ROOT%\server"
set "PORT=4000"

echo.
echo  =============================================
echo    StreamNode Server Launcher
echo  =============================================
echo.

:: ── Check Node.js ─────────────────────────────────────────────────────────────
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] Node.js not found. Install from https://nodejs.org
    pause & exit /b 1
)

:: ── Install dependencies if missing ───────────────────────────────────────────
if not exist "%SERVER_DIR%\node_modules\ws" (
    echo  [Setup] Installing dependencies...
    pushd "%SERVER_DIR%"
    call npm install
    popd
    echo.
)

:: ── Load .env ─────────────────────────────────────────────────────────────────
if exist "%SERVER_DIR%\.env" (
    echo  [Config] Loaded .env
    for /f "usebackq tokens=1,* delims==" %%A in ("%SERVER_DIR%\.env") do (
        set "line=%%A"
        if not "!line:~0,1!"=="#" if not "%%A"=="" set "%%A=%%B"
    )
) else (
    echo  [WARN] No .env found — using defaults.
)

:: ── Build TypeScript ──────────────────────────────────────────────────────────
echo  [1/2] Building TypeScript...
pushd "%SERVER_DIR%"
call npm run build
if %errorlevel% neq 0 (
    echo  [ERROR] TypeScript build failed. Check errors above.
    popd & pause & exit /b 1
)
popd

:: ── Start Node server in background window ────────────────────────────────────
echo  [2/2] Starting server on http://localhost:%PORT% ...
start "StreamNode Server" cmd /k "cd /d "%SERVER_DIR%" && node dist/server.js"

:: ── Wait for server health before opening browser ────────────────────────────
echo  [2/2] Waiting for server health check...
set /a RETRIES=0
:WAIT_LOOP
timeout /t 2 /nobreak >nul
curl -s -o nul -w "%%{http_code}" http://localhost:%PORT%/health | findstr "200" >nul 2>&1
if %errorlevel% equ 0 goto READY
set /a RETRIES+=1
if %RETRIES% lss 10 goto WAIT_LOOP
echo  [WARN] Server did not respond to /health in 20s — opening anyway

:READY
echo  Opening http://localhost:%PORT% in browser...
start "" "http://localhost:%PORT%"

echo.
echo  Server is running at http://localhost:%PORT%
echo  Close the "StreamNode Server" window to stop.
echo.
endlocal
