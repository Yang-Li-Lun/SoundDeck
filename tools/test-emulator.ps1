param([string]$Serial = 'emulator-5582', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^emulator-\d+$') { throw 'This script is restricted to an emulator. Install on a physical device explicitly.' }
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'evidence/runtime') | Out-Null
Push-Location $projectRoot
try {
    if (!$SkipBuild) { & ./tools/build.ps1 -Tasks @(':app:assembleDebug', ':app:assembleDebugAndroidTest') }
    if ((& adb -s $Serial shell getprop sys.boot_completed).Trim() -ne '1') { throw 'Emulator is not ready.' }
    & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    & adb -s $Serial install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
    $result = & adb -s $Serial shell am instrument -w -r dev.sounddeck.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $result | Tee-Object -FilePath evidence/runtime/instrumentation.txt
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Instrumentation did not pass; see evidence/runtime/instrumentation.txt.' }
} finally { Pop-Location }
