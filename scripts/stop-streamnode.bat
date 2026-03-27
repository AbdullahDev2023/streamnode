@echo off
title StreamNode — Stop
echo Stopping StreamNode services...
taskkill /FI "WindowTitle eq StreamNode Server*" /F >nul 2>&1
taskkill /FI "WindowTitle eq StreamNode ngrok*"  /F >nul 2>&1
taskkill /F /IM ngrok.exe >nul 2>&1
echo Done.
timeout /t 2 /nobreak >nul
