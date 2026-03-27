@echo off
echo ============================================================
echo  StreamNode — Grant Device Owner via ADB
echo ============================================================
echo.
echo  This script promotes StreamNode to Device Owner on a
echo  connected Android device. Device Owner unlocks:
echo    - Silent reboot
echo    - App install/uninstall without user prompt
echo    - System settings (brightness, screen lock timeout)
echo    - clearApplicationUserData
echo.
echo  REQUIREMENTS:
echo    1. USB debugging enabled on the phone
echo    2. No Google accounts or only one account on the device
echo       (Android blocks DPM if multiple accounts exist)
echo    3. adb in PATH  (Android SDK platform-tools)
echo.
echo  Run this ONCE after first install. Uninstalling the app
echo  automatically revokes Device Owner status.
echo.
set /p CONFIRM="Type YES to continue: "
if /i not "%CONFIRM%"=="YES" (
    echo Aborted.
    pause & exit /b 1
)
echo.
echo Checking ADB connection...
adb devices
echo.
echo Setting Device Owner...
adb shell dpm set-device-owner com.akdevelopers.streamnode/.deviceadmin.StreamNodeDeviceAdminReceiver
echo.
if %ERRORLEVEL% == 0 (
    echo ✅ Device Owner granted successfully!
    echo    Reboot the phone, then open StreamNode.
) else (
    echo ❌ Failed. Common causes:
    echo    - Multiple Google accounts on device (remove all, try again)
    echo    - ADB not connected / USB debugging not enabled
    echo    - Device Owner already set to a different app
    echo.
    echo Try:  adb shell pm list users
    echo       adb shell dumpsys device_policy
)
echo.
pause
