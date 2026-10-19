@echo off
rem Builds Dynamic Island Lab into a debug APK on Windows.
rem   build.bat            build  ->  dist\DynamicIslandLab-debug.apk
rem   build.bat install    build, then adb-install to a USB-connected phone
setlocal
cd /d "%~dp0"

rem ---- Android SDK ----
set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" set "SDK=%ANDROID_SDK_ROOT%"
if "%SDK%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if "%SDK%"=="" (
  echo ERROR: Android SDK not found. Install Android Studio or set ANDROID_HOME.
  exit /b 1
)
set "ANDROID_HOME=%SDK%"
set "SDK_FWD=%SDK:\=/%"
> local.properties echo sdk.dir=%SDK_FWD%
echo Android SDK: %SDK%

rem ---- Java: use Android Studio's bundled JDK if JAVA_HOME is unset ----
if "%JAVA_HOME%"=="" if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"

call gradlew.bat assembleDebug
if errorlevel 1 exit /b 1

if not exist dist mkdir dist
copy /y "app\build\outputs\apk\debug\app-debug.apk" "dist\DynamicIslandLab-debug.apk" >nul
echo.
echo APK ready: %CD%\dist\DynamicIslandLab-debug.apk

if /i "%~1"=="install" (
  "%SDK%\platform-tools\adb.exe" install -r "dist\DynamicIslandLab-debug.apk"
  "%SDK%\platform-tools\adb.exe" shell am start -n com.tara.dynamicisland/.MainActivity
) else (
  echo To install: build.bat install   ^(USB debugging on^), or copy the APK to your phone.
)
endlocal
