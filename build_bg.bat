@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%
cd /d "%~dp0"
start /B "" gradlew.bat :app:assembleDebug --no-daemon > build_background.log 2>&1
echo Build started in background. Check build_background.log
