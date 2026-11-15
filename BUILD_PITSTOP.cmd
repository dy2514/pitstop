@echo off
cd /d "%~dp0"
call gradlew.bat :pitstop:assembleDebug :pitstop-core:test :pitstop:testDebugUnitTest :pitstop:lintDebug
set "APP_EXIT=%ERRORLEVEL%"
pause
exit /b %APP_EXIT%
