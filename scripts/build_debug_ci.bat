@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0.."
echo JAVA_HOME=%JAVA_HOME%
java -version
echo.
echo Running assembleDebug...
call gradlew.bat assembleDebug --no-daemon
echo.
echo Exit code: %ERRORLEVEL%
