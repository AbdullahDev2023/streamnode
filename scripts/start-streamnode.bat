@echo off
setlocal enabledelayedexpansion
title StreamNode Launcher
color 0A

echo.
echo  =============================================
echo    StreamNode — Starting all services...
echo  =============================================
echo.

:: Root of the project (one level up from this scripts\ folder)
set "ROOT=%~dp0.."
set "SERVER_DIR=%ROOT%\server"

:: ── Load .env if it exists (preferred over hardcoding secrets here) ──────────
if exist "%SERVER_DIR%\.env" (
    echo  [Config] Loading %SERVER_DIR%\.env
    for /f "usebackq tokens=1,* delims==" %%A in ("%SERVER_DIR%\.env") do (
        set "line=%%A"
        if not "!line:~0,1!"=="#" if not "%%A"=="" (
            set "%%A=%%B"
        )
    )
) else (
    echo  [Config] No .env found.
    echo           Copy server\.env.example to server\.env and fill in secrets.
    echo.
)

:: ── Check Node.js ─────────────────────────────────────────────────────────────
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] Node.js not found. Install v18+ from https://nodejs.org
    pause & exit /b 1
)
for /f "tokens=*" %%v in ('node -e "process.stdout.write(process.version)"') do set NODE_VER=%%v
echo  [OK]    Node.js %NODE_VER% found.

:: ── Install dependencies if missing ──────────────────────────────────────────
if not exist "%SERVER_DIR%\node_modules\ws" (
    echo  [Setup] Installing server dependencies...
    pushd "%SERVER_DIR%"
    call npm install
    popd
)

:: ── Kill any process already on port 4000 ───────────────────────────────────
echo  [0/3] Checking for existing process on port 4000...
for /f "tokens=5" %%P in ('netstat -ano 2^>nul ^| findstr " :4000 " ^| findstr "LISTENING"') do (
    echo  [Kill] Killing PID %%P on port 4000...
    taskkill /PID %%P /F >nul 2>&1
)
timeout /t 1 /nobreak >nul

:: ── Build TypeScript ─────────────────────────────────────────────────────────
echo  [1/3] Building TypeScript...
pushd "%SERVER_DIR%"
call npm run build
if %errorlevel% neq 0 (
    echo  [ERROR] TypeScript build failed. Check errors above.
    popd & pause & exit /b 1
)
popd

:: ── Start Node server in a new window ────────────────────────────────────────
echo  [2/3] Starting StreamNode relay server on port 4000...
start "StreamNode Server" cmd /k "cd /d "%SERVER_DIR%" && node dist/server.js"
timeout /t 2 /nobreak >nul

:: ── Start ngrok tunnel ───────────────────────────────────────────────────────
echo  [2/3] Starting ngrok tunnel...
echo.
echo  -----------------------------------------------
echo   Static ngrok domain:
echo     wss://noniridescently-glyphographic-brant.ngrok-free.dev
echo  -----------------------------------------------
echo.

where ngrok >nul 2>&1
if %errorlevel% equ 0 (
    start "StreamNode ngrok" cmd /k "ngrok http --config="%USERPROFILE%\.ngrok2\ngrok-new.yml" --domain=noniridescently-glyphographic-brant.ngrok-free.dev 4000"
) else if exist "%ROOT%\ngrok.exe" (
    start "StreamNode ngrok" cmd /k "%ROOT%\ngrok.exe http --config="%USERPROFILE%\.ngrok2\ngrok-new.yml" --domain=noniridescently-glyphographic-brant.ngrok-free.dev 4000"
) else (
    echo  [WARN] ngrok.exe not found in PATH or project root.
    echo         Download from https://ngrok.com/download and place
    echo         ngrok.exe in the StreamNode\ root folder, then re-run.
    echo.
)

:: ── Open browser ─────────────────────────────────────────────────────────────
echo  [3/3] Opening dashboard in browser...
timeout /t 3 /nobreak >nul
start "" "http://localhost:4000"

echo.
echo  All services started.
echo  To stop: run stop-streamnode.bat or close the server/ngrok windows.
echo.
endlocal
pause
