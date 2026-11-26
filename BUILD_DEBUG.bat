@echo off
setlocal
cd /d "%~dp0"

rem ===========================================================================
rem OxigenPlayer Pro - o singura varianta, care include tot:
rem   - instalare directa din APK, cu auto-update propriu
rem   - HTTP permis doar catre reteaua locala (NAS, server local)
rem   - decodoare software FFmpeg (NextLib) ca rezerva
rem   - traducere ML Kit + provideri
rem Necesita Android 7.0+ (API 24).
rem ===========================================================================

set "SRC_APK=app\build\outputs\apk\debug\app-debug.apk"
set "OUT_DIR=apk-debug"
set "OUT_APK=%OUT_DIR%\OxigenPlayer-debug.apk"

echo [1/4] Curat APK-urile debug vechi din %OUT_DIR%\ ...
if exist "%OUT_DIR%" del /q "%OUT_DIR%\app-*-debug.apk" >nul 2>&1

echo [2/4] Build :app:assembleDebug ...
rem %~dp0 = calea absoluta a acestui script. Este necesara pentru ca pe acest
rem sistem cmd nu cauta in directorul curent (NoDefaultCurrentDirectoryInExePath).
call "%~dp0gradlew.bat" --console=plain :app:assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD ESUAT. Vezi mesajele de mai sus.
    exit /b 1
)

if not exist "%SRC_APK%" (
    echo.
    echo APK-ul asteptat nu a fost generat: %SRC_APK%
    exit /b 1
)

echo [3/4] Copiez APK-ul in %OUT_APK% ...
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
copy /y "%SRC_APK%" "%OUT_APK%" >nul
if errorlevel 1 (
    echo.
    echo COPIERE ESUATA.
    exit /b 1
)

echo [4/4] Gata.
echo.
echo   APK debug: %OUT_APK%
echo.
echo Instalare pe Android TV (cu adb conectat):
echo   adb install -r -t "%OUT_APK%"
echo Jurnale:
echo   adb logcat -s OxigenPlayer:* AndroidRuntime:E
echo.
endlocal
