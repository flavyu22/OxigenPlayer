@echo off
setlocal
cd /d "%~dp0"
rem Verificare completa (teste + lint + APK) pe singura varianta a aplicatiei.
rem Pentru build rapid (fara teste/lint) foloseste BUILD_DEBUG.bat.
call "%~dp0gradlew.bat" --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
if errorlevel 1 exit /b 1
echo APK: app\build\outputs\apk\debug\app-debug.apk
echo Connect an Android TV via adb, then run:
echo gradlew.bat :app:connectedDebugAndroidTest
endlocal
