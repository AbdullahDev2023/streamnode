@echo off
setlocal
set "JAVA_HOME=C:\jbr21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
cd /d "%~dp0.."

echo ============================================
echo   StreamNode — Clean + Rebuild
echo ============================================
echo.
echo JAVA_HOME=%JAVA_HOME%
java -version 2>&1
echo.

echo === Step 1: Cleaning project ===
call gradlew.bat clean
if %ERRORLEVEL% NEQ 0 (
    echo CLEAN FAILED — check output above.
    pause >nul
    exit /b %ERRORLEVEL%
)
echo Clean complete.
echo.

echo === Step 2: Building assembleDebug ===
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED — check output above.
    pause >nul
    exit /b %ERRORLEVEL%
)
echo.
echo === Build successful! ===
echo === APK: app\build\outputs\apk\debug\app-debug.apk ===
echo.

echo === Step 3: Checking for connected ADB device ===
"%ADB%" devices
"%ADB%" get-state >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo === Installing APK on connected device ===
    "%ADB%" install -r "app\build\outputs\apk\debug\app-debug.apk"
    if %ERRORLEVEL% EQU 0 (
        echo === Launching SetupActivity ===
        "%ADB%" shell am start -n com.akdevelopers.streamnode/.ui.setup.SetupActivity
    ) else (
        echo INSTALL FAILED — check device connection.
    )
) else (
    echo === No device connected — skipping install ===
    echo    Connect phone via USB with USB Debugging enabled, then run:
    echo    %ADB% install -r app\build\outputs\apk\debug\app-debug.apk
)

echo.
echo === Done. Press any key to close ===
pause >nul
endlocal
