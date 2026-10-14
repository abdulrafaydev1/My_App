@echo off
setlocal enabledelayedexpansion

echo =======================================================
echo          InstaGuard Local APK Build Script
echo =======================================================
echo.

:: 1. Check for Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java (JDK 17+) was not found in your PATH!
    echo.
    echo Please install OpenJDK 17 using winget:
    echo     winget install Microsoft.OpenJDK.17
    echo or download from:
    echo     https://adoptium.net/temurin/releases/?version=17
    echo.
    echo After installing, restart your terminal and run this script again.
    pause
    exit /b 1
)

echo [OK] Java is available:
java -version
echo.

:: 2. Check for ANDROID_HOME or ANDROID_SDK_ROOT
if "%ANDROID_HOME%"=="" (
    if "%ANDROID_SDK_ROOT%"=="" (
        echo [WARNING] Neither ANDROID_HOME nor ANDROID_SDK_ROOT environment variable is set.
        echo If you have Android SDK installed (e.g., at %%LOCALAPPDATA%%\Android\Sdk),
        echo run:
        echo     set ANDROID_HOME=%%LOCALAPPDATA%%\Android\Sdk
        echo.
    )
)

:: 3. Check for gradle-wrapper.jar
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [INFO] gradle-wrapper.jar not found. Downloading Gradle Wrapper JAR...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.jar')"
    if not exist "gradle\wrapper\gradle-wrapper.jar" (
        echo [WARNING] Automatic download failed. Generating wrapper via system gradle if present...
        gradle wrapper --gradle-version 8.5
    )
)

:: 4. Run Gradle Build
echo [BUILDING] Compiling debug APK with Gradle...
call gradlew.bat assembleDebug --stacktrace

if %ERRORLEVEL% equ 0 (
    echo.
    echo =======================================================
    echo [SUCCESS] APK compiled successfully!
    echo Output APK path:
    echo %CD%\app\build\outputs\apk\debug\app-debug.apk
    echo =======================================================
) else (
    echo.
    echo [FAILED] Gradle build failed with error code %ERRORLEVEL%.
    echo Note: You can also push this repository to GitHub to build
    echo the APK automatically with zero local configuration!
)

echo.
pause
