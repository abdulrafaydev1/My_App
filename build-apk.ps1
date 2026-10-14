# InstaGuard PowerShell Build Script
Write-Host "=======================================================" -ForegroundColor Cyan
Write-Host "       InstaGuard Local APK Build Script (PowerShell)   " -ForegroundColor Cyan
Write-Host "=======================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Check for Java
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Host "[ERROR] Java (JDK 17+) was not found in your PATH." -ForegroundColor Red
    Write-Host "Please install OpenJDK 17 using winget:" -ForegroundColor Yellow
    Write-Host "    winget install Microsoft.OpenJDK.17" -ForegroundColor White
    Write-Host "Or download from Adoptium:" -ForegroundColor Yellow
    Write-Host "    https://adoptium.net/temurin/releases/?version=17" -ForegroundColor White
    Write-Host ""
    exit 1
}

Write-Host "[OK] Java found: $($javaCmd.Source)" -ForegroundColor Green
& java -version
Write-Host ""

# 2. Check Android SDK
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $defaultSdk = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $defaultSdk) {
        Write-Host "[INFO] Detected Android SDK at $defaultSdk. Setting ANDROID_HOME." -ForegroundColor Green
        $env:ANDROID_HOME = $defaultSdk
    } else {
        Write-Host "[WARNING] Neither ANDROID_HOME nor ANDROID_SDK_ROOT is set." -ForegroundColor Yellow
        Write-Host "Set it manually if the build fails to locate the Android SDK." -ForegroundColor Gray
    }
}

# 3. Ensure Gradle Wrapper Jar
$wrapperJar = "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $wrapperJar)) {
    Write-Host "[INFO] Downloading gradle-wrapper.jar..." -ForegroundColor Cyan
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $webClient = New-Object Net.WebClient
        $webClient.DownloadFile("https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar", $wrapperJar)
        Write-Host "[OK] gradle-wrapper.jar downloaded." -ForegroundColor Green
    } catch {
        Write-Host "[WARNING] Could not download gradle-wrapper.jar directly: $_" -ForegroundColor Yellow
    }
}

# 4. Compile APK
Write-Host "[BUILDING] Running gradlew assembleDebug..." -ForegroundColor Cyan
& .\gradlew.bat assembleDebug --stacktrace

if ($LASTEXITCODE -eq 0) {
    $apkPath = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
    Write-Host ""
    Write-Host "=======================================================" -ForegroundColor Green
    Write-Host "[SUCCESS] APK built successfully!" -ForegroundColor Green
    Write-Host "Output Path: $apkPath" -ForegroundColor White
    Write-Host "=======================================================" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "[FAILED] Build failed with exit code $LASTEXITCODE." -ForegroundColor Red
    Write-Host "Tip: You can push this repository to GitHub and use the included" -ForegroundColor Yellow
    Write-Host "GitHub Actions workflow to build the APK in the cloud with zero setup!" -ForegroundColor Yellow
}
