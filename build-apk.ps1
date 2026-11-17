<#
.SYNOPSIS
    Builds the Android APK on-demand for manual sideloading with a dynamic filename.
.DESCRIPTION
    This script cleans the project, compiles the debug APK, dynamically extracts
    the application name and version string, and saves the final package to 'build-output'.
#>

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Starting Android APK Build Process      " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Check for Gradle Wrapper
if (-not (Test-Path ".\gradlew.bat")) {
    Write-Error "Error: gradlew.bat not found in the current directory ($ProjectRoot)."
    Exit 1
}

# 2. Extract Application Name and Version Name dynamically
$AppName = "App"
$StringsFile = ".\app\src\main\res\values\strings.xml"
if (Test-Path $StringsFile) {
    $StringsContent = Get-Content $StringsFile -Raw
    if ($StringsContent -match '<string name="app_name">(.*?)</string>') {
        $AppName = $Matches[1].Trim()
    }
}

$VersionName = ""
$GradleFile = ".\app\build.gradle.kts"
if (Test-Path $GradleFile) {
    $GradleContent = Get-Content $GradleFile
    foreach ($Line in $GradleContent) {
        if ($Line -match 'versionName\s*=\s*"([^"]+)"') {
            $VersionName = "-v" + $Matches[1]
            break
        }
    }
}

# Clean forbidden filename characters if any exist
$AppName = $AppName -replace '[\\/:*?"<>|]', ''

# 3. Run clean and assembleDebug
Write-Host "`n[1/2] Compiling project and building APK..." -ForegroundColor Yellow
.\gradlew.bat clean app:assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[-] Build Failed! Please check the logs above for syntax or compilation errors." -ForegroundColor Red
    Exit $LASTEXITCODE
}

# 4. Locate the generated APK and copy with dynamic name
$SourceApk = ".\app\build\outputs\apk\debug\app-debug.apk"
$OutputFolder = ".\build-output"
$TargetFileName = "${AppName}${VersionName}.apk"
$TargetApk = Join-Path $OutputFolder $TargetFileName

if (Test-Path $SourceApk) {
    # Ensure output folder exists
    if (-not (Test-Path $OutputFolder)) {
        New-Item -ItemType Directory -Path $OutputFolder | Out-Null
    }

    # Copy APK to output folder with custom name
    Copy-Item -Path $SourceApk -Destination $TargetApk -Force

    Write-Host "`n=========================================" -ForegroundColor Green
    Write-Host " [+] SUCCESS! Build completed successfully." -ForegroundColor Green
    Write-Host " Your custom named APK is ready at:" -ForegroundColor Green
    Write-Host " $ProjectRoot\build-output\$TargetFileName" -ForegroundColor White
    Write-Host "=========================================" -ForegroundColor Green
} else {
    Write-Host "`n[-] Compilation finished but the output APK could not be found at standard location." -ForegroundColor Red
    Exit 1
}
