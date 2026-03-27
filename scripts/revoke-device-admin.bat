@echo off
echo ============================================================
echo  StreamNode — Revoke Device Admin / Device Owner
echo ============================================================
echo.
echo  USE CASE: Dev/test cleanup, or before uninstalling.
echo  This removes both Device Admin and Device Owner privileges.
echo.
echo  Option A (Device Owner — requires ADB):
echo    adb shell dpm remove-active-admin com.akdevelopers.streamnode/.deviceadmin.StreamNodeDeviceAdminReceiver
echo.
echo  Option B (Device Admin — on the phone):
echo    Settings ^> Security ^> Device Admins ^> StreamNode ^> Deactivate
echo.
set /p WHICH="Press A for ADB removal, B for instructions, or ENTER to exit: "
if /i "%WHICH%"=="A" (
    echo.
    echo Removing via ADB...
    adb shell dpm remove-active-admin com.akdevelopers.streamnode/.deviceadmin.StreamNodeDeviceAdminReceiver
    echo Done.
)
if /i "%WHICH%"=="B" (
    echo.
    echo On the phone:
    echo   Settings ^> Security ^> Device Admin Apps ^> StreamNode ^> Deactivate
    echo.
    echo Then you can safely uninstall the app.
)
echo.
pause
