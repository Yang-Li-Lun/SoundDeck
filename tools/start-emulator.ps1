param([string]$ImageDirectory, [int]$Port = 5582)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK.' }
if (!$ImageDirectory) { $ImageDirectory = Join-Path $env:ANDROID_HOME 'system-images/android-36/google_apis/x86_64' }
$imagePath = (Resolve-Path -LiteralPath $ImageDirectory).Path
if (!(Test-Path (Join-Path $imagePath 'kernel-ranchu'))) { throw 'Android 16 x86_64 system image is incomplete.' }
$avdRoot = Join-Path $projectRoot '.local/avd'
$avdName = 'SoundDeck_Api36'
$avdPath = Join-Path $avdRoot "$avdName.avd"
New-Item -ItemType Directory -Force -Path $avdPath,(Join-Path $projectRoot 'evidence/runtime') | Out-Null
if (!(Test-Path (Join-Path $avdPath 'config.ini'))) {
    @"
AvdId=$avdName
avd.ini.displayname=SoundDeck Android 16
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=4096
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.keyboard=yes
hw.audioInput=no
hw.camera.back=none
hw.camera.front=none
hw.sensors.orientation=yes
hw.mainKeys=no
disk.dataPartition.size=4G
fastboot.forceColdBoot=yes
image.sysdir.1=$imagePath\
tag.id=google_apis
tag.display=Google APIs
PlayStore.enabled=false
"@ | Set-Content (Join-Path $avdPath 'config.ini') -Encoding utf8
}
@"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-36
"@ | Set-Content (Join-Path $avdRoot "$avdName.ini") -Encoding utf8
$previousAvd = $env:ANDROID_AVD_HOME
$previousUser = $env:ANDROID_USER_HOME
try {
    $env:ANDROID_AVD_HOME = $avdRoot
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.local/android'
    $process = Start-Process -FilePath (Join-Path $env:ANDROID_HOME 'emulator/emulator.exe') -ArgumentList @(
        '-avd',$avdName,'-port',"$Port",'-no-window','-no-audio','-no-snapshot','-no-boot-anim',
        '-gpu','swiftshader','-feature','-Vulkan','-memory','4096') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $projectRoot 'evidence/runtime/emulator-api36.log') `
        -RedirectStandardError (Join-Path $projectRoot 'evidence/runtime/emulator-api36-error.log')
    $process.Id | Set-Content (Join-Path $projectRoot '.local/emulator.pid')
    Write-Output "Started project-local AVD $avdName, PID $($process.Id), device emulator-$Port"
} finally {
    $env:ANDROID_AVD_HOME = $previousAvd
    $env:ANDROID_USER_HOME = $previousUser
}
