# Сборка debug-APK и установка на подключённый телефон.
# Запуск:  powershell -ExecutionPolicy Bypass -File install.ps1

$ErrorActionPreference = "Stop"

$project = "E:\project\android\myradio"
$adb = "C:\Users\lega\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "E:\project\tools\jdk21"

Push-Location $project
try {
    Write-Host "== Сборка ==" -ForegroundColor Cyan
    & "$project\gradlew.bat" assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Сборка упала с кодом $LASTEXITCODE" }

    $apk = Join-Path $project "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) { throw "APK не найден: $apk" }

    Write-Host "== Устройства ==" -ForegroundColor Cyan
    & $adb devices

    Write-Host "== Установка ==" -ForegroundColor Cyan
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Установка не прошла" }

    Write-Host "== Запуск ==" -ForegroundColor Cyan
    & $adb shell am start -n ru.lega.radio/.MainActivity
}
finally {
    Pop-Location
}
