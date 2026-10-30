param([string]$Serial='emulator-5582', [string]$EvidenceDirectory='evidence/0.6.0/screenshots', [string[]]$ScenarioNames=@())
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if ($Serial -ne 'emulator-5582' -or ((& adb -s $Serial emu avd name) -join "`n") -notmatch '(?m)^SoundDeck_Api36\s*$') { throw 'Only the SoundDeck test AVD is allowed.' }
$evidenceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if(!$evidenceRoot.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must remain in this project'}
New-Item -ItemType Directory -Force $evidenceRoot | Out-Null
$font=(& adb -s $Serial shell settings get system font_scale).Trim()
$sizeBefore=(& adb -s $Serial shell wm size) -join "`n"
$densityBefore=(& adb -s $Serial shell wm density) -join "`n"
$nightBefore=(& adb -s $Serial shell cmd uimode night) -join "`n"
$navBefore=(& adb -s $Serial shell cmd overlay list) -join "`n"
$sizeRestore=if($sizeBefore -match 'Override size: ([0-9]+x[0-9]+)') {$Matches[1]} else {'reset'}
$densityRestore=if($densityBefore -match 'Override density: ([0-9]+)') {$Matches[1]} else {'reset'}
$nightRestore=if($nightBefore -match 'Night mode: (yes|no|auto|custom)') {$Matches[1]} else {'no'}
$navRestore=if($navBefore -match '\[x\] (com.android.internal.systemui.navbar.[a-z]+)') {$Matches[1]} else {'com.android.internal.systemui.navbar.gestural'}
function Read-Ui([string]$Name) {
    & adb -s $Serial shell uiautomator dump /sdcard/sounddeck-layout.xml | Out-Null
    $path=Join-Path $evidenceRoot "$Name.xml"
    & adb -s $Serial pull /sdcard/sounddeck-layout.xml $path | Out-Null
    return [xml](Get-Content -Raw -LiteralPath $path)
}
function Save-Screen([string]$Name) {
    & adb -s $Serial shell screencap -p /sdcard/sounddeck-layout.png
    & adb -s $Serial pull /sdcard/sounddeck-layout.png (Join-Path $evidenceRoot "$Name.png") | Out-Null
}
function Tap-Node($Node) {
    if (!$Node -or $Node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Target missing' }
    & adb -s $Serial shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2))
}
function Find-Node($Ui,[string]$Text) {
    return $Ui.SelectNodes('//node') | Where-Object {$_.text -eq $Text -or $_.'content-desc' -eq $Text} | Select-Object -First 1
}
function Largest-Scroll($Ui) {
    return $Ui.SelectNodes('//node') | Where-Object {$_.scrollable -eq 'true'} | Sort-Object -Descending -Property @{Expression={
        if($_.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){([int]$Matches[3]-[int]$Matches[1])*([int]$Matches[4]-[int]$Matches[2])}else{0}
    }} | Select-Object -First 1
}
function Scroll-End([string]$Name) {
    $ui=Read-Ui $Name
    $scroll=Largest-Scroll $ui
    if($scroll -and $scroll.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$top=[int]$Matches[2];$bottom=[int]$Matches[4]
        for($step=0;$step -lt 8;$step++){ & adb -s $Serial shell input swipe $x ([int]($bottom-($bottom-$top)*0.2)) $x ([int]($top+($bottom-$top)*0.2)) 120 }
    }
    return Read-Ui $Name
}
function Reach-Node([string]$Text,[string]$Name) {
    for($step=0;$step -lt 20;$step++) {
        $ui=Read-Ui $Name
        $node=Find-Node $ui $Text
        if($node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and ([int]$Matches[4]-[int]$Matches[2]) -ge 24){return $node}
        $scroll=Largest-Scroll $ui
        if(!$scroll -or $scroll.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){throw "Scrollable area missing for $Text"}
        $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$top=[int]$Matches[2];$bottom=[int]$Matches[4]
        & adb -s $Serial shell input swipe $x ([int]($bottom-($bottom-$top)*0.2)) $x ([int]($top+($bottom-$top)*0.2)) 300
    }
    throw "Could not reach $Text"
}
try {
    $scenarios=@(
        @{Name='display-large-font2'; Size='1080x2400'; Density='560'; Font='2.0'; Night='yes'; Nav='threebutton'},
        @{Name='phone-light'; Size='1080x2400'; Density='420'; Font='1.0'; Night='no'; Nav='gestural'},
        @{Name='phone-dark'; Size='1080x2400'; Density='420'; Font='1.0'; Night='yes'; Nav='gestural'},
        @{Name='small-font2-light'; Size='840x1680'; Density='420'; Font='2.0'; Night='no'; Nav='gestural'},
        @{Name='small-font2-dark'; Size='840x1680'; Density='420'; Font='2.0'; Night='yes'; Nav='gestural'},
        @{Name='wide'; Size='2400x1680'; Density='420'; Font='1.0'; Night='no'; Nav='gestural'},
        @{Name='threebutton-light'; Size='1080x2400'; Density='420'; Font='1.0'; Night='no'; Nav='threebutton'},
        @{Name='threebutton-dark'; Size='840x1680'; Density='420'; Font='2.0'; Night='yes'; Nav='threebutton'},
        @{Name='landscape-font2-light'; Size='1680x840'; Density='420'; Font='2.0'; Night='no'; Nav='gestural'},
        @{Name='landscape-font2-threebutton'; Size='1680x840'; Density='420'; Font='2.0'; Night='yes'; Nav='threebutton'},
        @{Name='compact-window-font2'; Size='840x945'; Density='420'; Font='2.0'; Night='yes'; Nav='threebutton'}
    )
    if($ScenarioNames.Count -gt 0){$scenarios=@($scenarios | Where-Object {$_.Name -in $ScenarioNames}); if($scenarios.Count -ne $ScenarioNames.Count){throw "Unknown scenario name"}}
    foreach($scenario in $scenarios) {
        & adb -s $Serial shell wm size $scenario.Size
        & adb -s $Serial shell wm density $scenario.Density
        & adb -s $Serial shell settings put system font_scale $scenario.Font
        & adb -s $Serial shell cmd uimode night $scenario.Night | Out-Null
        & adb -s $Serial shell cmd overlay enable-exclusive --category ('com.android.internal.systemui.navbar.'+$scenario.Nav)
        & adb -s $Serial shell am force-stop dev.sounddeck
        & adb -s $Serial shell am start -W -n dev.sounddeck/.MainActivity | Out-Null
        $ui=Read-Ui ($scenario.Name+'-home'); Save-Screen ($scenario.Name+'-home')
        $connect=Find-Node $ui '連接耳機'
        if(!$connect){$connect=Reach-Node '連接耳機' ($scenario.Name+'-home')}
        Tap-Node $connect
        $dialog=Read-Ui ($scenario.Name+'-devices'); Save-Screen ($scenario.Name+'-devices')
        $null=Reach-Node 'RFCOMM' ($scenario.Name+'-devices-controls')
        $dialog=Read-Ui ($scenario.Name+'-devices-controls'); Save-Screen ($scenario.Name+'-devices-controls')
        Tap-Node (Find-Node $dialog 'RFCOMM')
        $cancel=Find-Node $dialog '取消'
        if(!$cancel){throw 'Device dialog dismiss control missing'}
        Tap-Node $cancel
        $end=Scroll-End ($scenario.Name+'-listening-bottom'); if(!(Find-Node $end 'Speak-to-Chat')){throw 'Listening end missing'}; Save-Screen ($scenario.Name+'-listening-bottom')
        $ui=Read-Ui ($scenario.Name+'-listening-bottom')
        Tap-Node (Find-Node $ui '音效')
        $ui=Read-Ui ($scenario.Name+'-effects'); Save-Screen ($scenario.Name+'-effects')
        $end=Scroll-End ($scenario.Name+'-effects-bottom'); if(!(Find-Node $end 'DSEE')){throw 'Effects end missing'}; if(Find-Node $end 'Speak-to-Chat'){throw 'Duplicate Speak-to-Chat controls'}; Save-Screen ($scenario.Name+'-effects-bottom')
        $ui=Read-Ui ($scenario.Name+'-effects-bottom')
        Tap-Node (Find-Node $ui '設定')
        $ui=Read-Ui ($scenario.Name+'-settings'); Save-Screen ($scenario.Name+'-settings')
        if(Find-Node $ui '通知欄控制'){throw 'Removed controls remain'}
        $license=Reach-Node '開源授權' ($scenario.Name+'-settings-bottom'); Save-Screen ($scenario.Name+'-settings-bottom')
        Tap-Node $license
        $dialog=Read-Ui ($scenario.Name+'-licenses'); Save-Screen ($scenario.Name+'-licenses')
        $done=Find-Node $dialog '完成'
        if(!$done){throw 'License dialog close control missing'}
        Tap-Node $done
        $ui=Read-Ui ($scenario.Name+'-closed')
        if(Find-Node $ui '完成'){throw 'License dialog button was not actionable'}
        Write-Output "PASS $($scenario.Name): all three pages, device dialog cancel and licenses done are reachable."
    }
} finally {
    & adb -s $Serial shell wm size $sizeRestore
    & adb -s $Serial shell wm density $densityRestore
    if($font -eq 'null') { & adb -s $Serial shell settings delete system font_scale } else { & adb -s $Serial shell settings put system font_scale $font }
    & adb -s $Serial shell cmd uimode night $nightRestore | Out-Null
    & adb -s $Serial shell cmd overlay enable-exclusive --category $navRestore
}
