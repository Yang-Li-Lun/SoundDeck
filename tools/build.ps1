param([string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$ipcPath = Join-Path $projectRoot '.ipc'
New-Item -ItemType Directory -Path $ipcPath -Force | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $projectRoot
try {
    # Confirmed Windows JDK Unix-domain socket failure. Only this process tree is changed.
    $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' "-Djdk.net.unixdomain.tmpdir=' + $ipcPath + '"').Trim()
    & .\gradlew.bat @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
