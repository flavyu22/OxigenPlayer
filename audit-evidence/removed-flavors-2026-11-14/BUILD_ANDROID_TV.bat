@echo off
setlocal
cd /d "%~dp0"
rem Verificare completa pe UN SINGUR model de debug: DirectFullApi24Debug.
rem Pentru build rapid (fara teste/lint) foloseste BUILD_DEBUG.bat.
rem Explicatia variantelor: VARIANTE_BUILD.md
call "%~dp0gradlew.bat" --no-daemon :app:testDirectFullApi24DebugUnitTest :app:lintDirectFullApi24Debug :app:assembleDirectFullApi24Debug
if errorlevel 1 exit /b 1
echo APK: app\build\outputs\apk\directFullApi24\debug\app-direct-full-api24-debug.apk
echo Connect an Android TV via adb, then run:
echo gradlew.bat :app:connectedDirectFullApi24DebugAndroidTest
endlocal
