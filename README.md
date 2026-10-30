# SoundDeck v0.15.1

原生 Android 耳機控制專案，使用 Kotlin、Jetpack Compose 與 Material 3，控制目標為 Sony WF-1000XM5。這是非官方專案，與 Sony 無隸屬關係。App 沒有帳號或網路權限，不需要 Root 或無障礙服務。

## 功能與邊界

- Sony MDR v2 控制：已配對記錄的 BLE GATT／RFCOMM 選路、型號與能力檢查；各入口共用同一 Repository、Controller 與 Session。
- ANC／Ambient／Off、環境聲音 1–20、App 內聚焦人聲；EQ 預設與受支援的六段自訂、DSEE、Speak-to-Chat 開關及 Type 2 語音偵測靈敏度。控制選項依耳機能力與有效回報啟用。
- Speak-to-Chat 開關與偵測靈敏度位於「聆聽」頁；「音效」頁提供五段直向 EQ、下方獨立的水平 Clear Bass 與 DSEE。400 Hz、1 kHz、2.5 kHz、6.3 kHz、16 kHz 在一般手機寬度均勻並排；極窄視窗或大字體時換列，保持至少 48dp 觸控範圍，無須橫向捲動。增益支援正負號、零點參考線、整數刻度、無障礙與鍵盤操作；目前值與耳機已確認值分別顯示。
- 自訂 EQ 每 150ms 合併最新目標，逐筆寫入並回讀確認；拖曳結束後自動送出最後值。確認期間可繼續調整頻段，其他設定暫停；失敗時保留耳機回報值，斷線後不重送未確認的目標，重連時重新讀取狀態。
- 左耳／充電盒／右耳電量、4×3 Widget、Quick Settings Tile 與前景服務狀態通知。Widget 提供三模式、十個環境聲音刻度及 EQ 切換；通知顯示模式與電量。
- 寫入後回讀相符才顯示成功；ACK 只代表封包收到。未知格式、逾時或不符回讀不會被當作成功，不盲目重送寫入。
- App 前景開啟及藍牙恢復時可嘗試重連已成功的裝置／通道；沒有開機自啟動或背景無限重試。

**v0.15.1 尚未完成實體 WF-1000XM5 驗收。** 上述為程式實作範圍，不保證各韌體或手機可用。實際無線寫入、聽感、LE Audio／LC3 音訊並行、Sound Connect 共存、多點情境與 OEM 背景限制仍待確認。min SDK 26 是建置支援下限，不表示各 Android 版本皆已實測。

充電盒電量使用目前 Session 最後有效回報，不保證持續即時更新；未知或斷線會清除。Launcher 可能保留程序結束前的 Widget 畫面，操作時仍需檢查連線。Widget 最小宣告為 250×180dp；較矮尺寸與十等分刻度的觸控空間受 Launcher 限制。

觸控配置、循環模式、Speak-to-Chat 結束時間編輯、配戴控制、多點管理、自適應聲音、音質模式、韌體更新、雲端／帳號與其他耳機型號尚未實作或啟用。

## 環境需求

- JDK 17 或 21；本機建置驗證使用 JDK 21。Gradle 由 Wrapper 下載，無須另外安裝。
- Android SDK Command-line Tools、Platform Tools、Android SDK Platform 36、Build Tools 36.0.0。
- Windows 建議 PowerShell 7；Android Studio 可選。其他系統可直接使用 `./gradlew`，尚未在此版本驗證。
- 首次建置需要連網下載 Gradle 與 Google Maven／Maven Central 依賴；不需要私人套件庫、Token 或原開發環境。

固定版本：Gradle 9.5.0（Wrapper 含 SHA-256）、AGP 9.2.1、Compose compiler plugin 2.3.20、Compose BOM 2026.04.01；compile／target SDK 36、min SDK 26。詳細依賴見建置檔。

從 [Android 官方工具頁](https://developer.android.com/studio#command-tools) 取得 SDK Command-line Tools，將其 `bin` 與 SDK `platform-tools` 加入目前工作階段 PATH，再用 SDK Manager 安裝：

```powershell
sdkmanager --licenses
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
```

設定目前工作階段的環境變數，路徑換成自己的安裝位置：

```powershell
$env:JAVA_HOME = '<JDK 安裝目錄>'
$env:ANDROID_HOME = '<Android SDK 安裝目錄>'
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:PATH"
./tools/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
```

也可在 Android Studio 設定 SDK，或使用不提交的 `local.properties` 指定 `sdk.dir`。直接建置：Windows 為 `./gradlew.bat :app:assembleDebug`，其他系統為 `./gradlew :app:assembleDebug`。

`tools/build.ps1` 使用從專案位置推導的 `.ipc`，處理已觀察到的 Windows JDK Unix-domain socket 問題；只設定建置程序的 Java 選項並於結束後恢復。不修改全域 TEMP／TMP、hosts 或 JDK。

輸出：

- App：`app/build/outputs/apk/debug/app-debug.apk`
- Android 測試 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- JVM 測試與 Lint 報告：`app/build/reports/`

首次 Debug 建置會以 JDK `keytool` 在 `.local/debug.keystore` **產生自己的開發金鑰**，既有金鑰不會被覆寫。此目錄由建置建立，不需從他人取得。`android` 密碼是 Debug 開發用途的公開預設值，不能作為正式發行金鑰。不同開發者產生的 APK 簽章不同，不能覆蓋安裝原簽章版本；請使用獨立測試裝置／模擬器。

Release 建置使用同一個既有開發憑證，保留本機交付版的安裝簽章；APK 為 Release variant、不可偵錯，但仍屬開發憑證簽署。發布前必須已有 `.local/debug.keystore`，不會為 Release 另建或替換金鑰：

```powershell
./tools/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintRelease', ':app:assembleRelease')
```

Release APK 位於 `app/build/outputs/apk/release/app-release.apk`。發布時遞增 `versionCode`／`versionName`，建立新的 `v<versionName>` 標籤與 GitHub Release，附上命名 APK、SHA-256 及驗證範圍，不覆寫歷史標籤或附件；憑證不提交。

## 可選：模擬器測試

另安裝 `emulator` 與 `system-images;android-36;google_apis;x86_64`（需硬體虛擬化支援及足夠磁碟空間）。以下工具建立專案專用 AVD，資料放在忽略的 `.local`，預設從 `ANDROID_HOME` 取得映像；不需還原任何封存：

```powershell
sdkmanager 'emulator' 'system-images;android-36;google_apis;x86_64'
./tools/start-emulator.ps1
# 等待 emulator-5582 完成開機（adb shell getprop sys.boot_completed 回報 1）
./tools/test-emulator.ps1 -Serial emulator-5582 -SkipBuild
adb -s emulator-5582 emu kill
```

`test-emulator.ps1` 會安裝 App／測試 APK 並執行 instrumentation，只接受模擬器序號。額外的 `verify-ui-smoke.ps1`、`verify-launcher.py` 僅適用專案專用 `SoundDeck_Api36`／`emulator-5582`；後者需要 Python 3.9+（標準函式庫即可）。這些工具操作測試 Launcher／顯示設定並寫入本機證據；不可用於個人日用裝置。模擬測試不代表真實藍牙或耳機驗收。

## 專案結構與版本

- `app/src/main`：正式 Android 程式、資源與第三方告知。
- `app/src/test`、`app/src/androidTest`：JVM 與 Android 測試；synthetic 測試程式不納入正式 App。
- `gradle/wrapper`、根目錄建置檔：重新建置入口；`gradle/licenses` 保留 Gradle 告知。
- `tools`：建置與可選的專用模擬器驗證工具。
- `docs`：[協定](docs/PROTOCOL.md)、[LE Audio 邊界](docs/LE_AUDIO.md)、[實機清單](docs/DEVICE_TEST.md)、[驗證範圍](docs/VALIDATION.md)、[授權](docs/LICENSING.md)。

目前版本為 `v0.15.1`（versionCode 16）。交付檔為 `SoundDeck-0.15.1-release.apk` 及同名 `.sha256` 校驗檔，沿用 v0.15.0 的開發憑證；發布附件見 [GitHub Releases](https://github.com/Yang-Li-Lun/SoundDeck/releases)。Git 僅包含公開來源；SDK、快取、`.local`、參考來源副本、診斷／歷史測試紀錄及 `deliverables` 均不提交。重新建置的金鑰、環境與時間不同，APK 不保證逐位元組相同。驗證結果見 [驗證範圍](docs/VALIDATION.md)。

## 授權

專案自身尚未指定開源授權；公開可見不等同授予重用或散布權利。未替專案選定 LICENSE。第三方程式與工具仍受各自授權約束，見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及 [授權說明](docs/LICENSING.md)。Sony 與產品名稱屬各自權利人。
