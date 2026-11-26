@echo off
setlocal
cd /d "%~dp0"

rem ===========================================================================
rem OxigenPlayer Pro 2.2.4 - UN SINGUR model de debug: directFullApi24Debug
rem
rem   direct  = instalabil din APK (fara Play Store), auto-update propriu,
rem             HTTP/LAN permis explicit (NAS, SMB, DLNA, server local)
rem   full    = decodoare software FFmpeg (NextLib) ca rezerva; pe TV-box-uri
rem             unde hardware-ul respinge E-AC3 / HEVC 10-bit, redarea continua
rem   api24   = traducere standard (ML Kit + provideri), compatibil
rem             Android 7.0+ (API 24), fara motor AI on-device
rem
rem Numele descrie exact ce contine varianta (fost "classic" / "nano").
rem Explicatia completa: VARIANTE_BUILD.md
rem ===========================================================================

set "FLAVOR=DirectFullApi24"
set "FLAVOR_DIR=directFullApi24"
set "SRC_APK=app\build\outputs\apk\%FLAVOR_DIR%\debug\app-direct-full-api24-debug.apk"
set "OUT_DIR=apk-debug"
set "OUT_APK=%OUT_DIR%\OxigenPlayer-2.2.4-debug.apk"

echo [1/4] Curat APK-urile debug vechi din %OUT_DIR%\ ...
if exist "%OUT_DIR%" del /q "%OUT_DIR%\app-*-debug.apk" >nul 2>&1

echo [2/4] Build :app:assemble%FLAVOR%Debug ...
rem %~dp0 = calea absoluta a acestui script. Este necesara pentru ca pe acest
rem sistem cmd nu cauta in directorul curent (NoDefaultCurrentDirectoryInExePath).
call "%~dp0gradlew.bat" --console=plain :app:assemble%FLAVOR%Debug
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
echo   Varianta : %FLAVOR%Debug (singura construita)
echo.
echo Instalare pe Android TV (cu adb conectat):
echo   adb install -r -t "%OUT_APK%"
echo Jurnale:
echo   adb logcat -s OxigenPlayer:* AndroidRuntime:E
echo.
endlocal
