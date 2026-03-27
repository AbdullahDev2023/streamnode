@echo off
setlocal
set "JAVA_HOME=C:\jbr21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
cd /d "%~dp0.."
echo JAVA_HOME=%JAVA_HOME%
java -version 2>&1
echo.
echo === Running assembleDebug ===
call gradlew.bat assembleDebug
echo.
echo === Exit code: %ERRORLEVEL% ===
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED — check output above.
    exit /b %ERRORLEVEL%
)
echo.
echo === APK: app\build\outputs\apk\debug\app-debug.apk ===
echo.
echo === Checking for connected ADB device ===
"%ADB%" devices
"%ADB%" get-state >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo === Installing APK on connected device ===
    "%ADB%" install -r "app\build\outputs\apk\debug\app-debug.apk"
    echo === Launch SetupActivity ===
    "%ADB%" shell am start -n com.akdevelopers.streamnode/.ui.setup.SetupActivity
) else (
    echo === No device connected — skipping install ===
    echo    Connect phone via USB with USB Debugging enabled, then run:
    echo    %ADB% install -r app\build\outputs\apk\debug\app-debug.apk
)
echo.
echo === Done. Press any key to close ===
pause >nul
endlocal
