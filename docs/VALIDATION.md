# v0.15.1 驗證範圍

2026-10-30，基於 v0.15.0，versionName `0.15.1`／versionCode `16`。環境為本機 Windows、JDK 21.0.11、SDK Platform 36／Build Tools 36.0.0、專案專用 Android 36 模擬器 `SoundDeck_Api36`。2026-10-31 的發布沿用此 APK 與已通過的驗證結果；未新增實體耳機或原手機驗證證據。

- EQ 介面：一般手機寬度並排五段直向滑桿，下方為水平 Clear Bass；極窄視窗或大型字體換列。正負增益、低對比零點線、目前值／耳機已確認值、至少 48dp 觸控範圍、整數調整及鍵盤／無障礙語意均已實作。現有 Material 3 1.4.0 的 `VerticalSlider` 為內部 API，使用小型 Compose 直向控制項，不旋轉水平 Slider、不升級依賴。Clear Bass 沿用 Material 3 Slider，明確設定 48dp 滑塊高度。
- 映射：協議六段順序仍為 `[Clear Bass, 400 Hz, 1 kHz, 2.5 kHz, 6.3 kHz, 16 kHz]`；畫面 `-10..+10` 對應封包 `0..20`，零點為 10。UI 測試逐段調整並核對實際 `SonyProtocol.setEq` 封包。Repository、150ms 合併佇列、Controller、BLE／RFCOMM 選路及其他音訊程式未修改。
- JVM：146 tests，0 failures／errors。涵蓋 150ms 合併、最後目標送達、交易不取消、拒絕回讀、斷線清除、預設選擇及 synthetic Session／Controller 回讀。
- Debug／Release Lint：均為 0 errors、44 warnings（既有警告）。Debug App、Android 測試 APK、Release APK 均建置成功。
- Android instrumentation：24 個不同的相關測試通過，合併首次執行、未完成項目及針對性復測計數；未重跑無關的整套 Widget／通知測試。包含五段並排、八種 320–900dp／短視窗／深淺色／1–2 倍字體版面、六段映射、上滑增加／下滑減少、手指未離開前的即時回饋、拖曳不帶動頁面、整數／鍵盤／無障礙語意操作、停用狀態、自訂預設、等待確認、失敗恢復、重新連線狀態及離線啟動。
- 初次版面檢查發現 Clear Bass 控制高度為 44dp，修正為 48dp 後八種版面通過；測試 fixture 在停用控制項後的頁面捲動需要明確等待，補上同步後通過。最終受影響的 11 項測試結果為 `OK (11 tests)`。保留先前未通過紀錄，不將其列為通過。
- Release APK：`deliverables/SoundDeck-0.15.1-release.apk`，不可偵錯，沿用既有開發憑證。最終檔案與 v0.15.0 的簽章憑證 SHA-256 相同：`68cb39293107d8e356cce7a1bb4ae24d31890a65b63a3013b4dcdcda3cdb18c2`。已在專用模擬器以最終 APK 從 v0.15.0 執行 `adb install -r` 覆蓋安裝成功，首次安裝時間保留，版本確認為 16／0.15.1；最終 Release 冷啟動成功。
- APK SHA-256：`52bbc8d86bc98dffb0399091850358698177320e1ca60b619f058b31642f1290`。

本機證據位於 `evidence/local-0.15.1`：建置及 Lint 紀錄、instrumentation 紀錄與 `passed-tests.txt`、版面截圖、`signature-final.txt`、`package-final.txt`、`upgrade-final.txt`。截圖使用 synthetic 耳機狀態；不是實體耳機連線證據。

尚未驗證：原手機安裝與手感、實體 WF-1000XM5 的 BLE 寫入／回讀及重連、實際 TalkBack／Switch Access 使用、Android 26–35 等其他版本、音訊並行／LC3 與聽感。模擬器及 synthetic 測試不代表上述項目通過。

## v0.15.0 歷史驗證範圍

2026-10-30，Windows／JDK 21.0.11、SDK Platform 36／Build Tools 36.0.0。以目前工作區的連線恢復與診斷實作為基礎，新增聆聽頁 Speak-to-Chat、六段直向 EQ 與合併即時套用；沒有修改 MDR 封包格式或 BLE 選路。

- JVM：146 個不同測試通過，以完整執行與 Controller 13 項針對性復測合併計數。涵蓋連續拖曳限流、最後目標送達、進行中交易不取消、重複目標、回讀拒絕停止、斷線清除，以及實際 Session／Controller 的 synthetic EQ 寫入與回讀。
- Debug／Release Lint：均為 0 errors、44 warnings。
- Debug App、Android 測試 APK、Release APK：建置成功。Release 使用既有 `.local/debug.keystore`，與 0.14.0-diagnostic 交付 APK 的憑證 SHA-256 相符：`68cb39293107d8e356cce7a1bb4ae24d31890a65b63a3013b4dcdcda3cdb18c2`。這是開發憑證簽署，Release APK 不可偵錯。
- Android 36 專案專用模擬器：47 個不同 instrumentation 測試通過（小視窗針對性復測 1 項＋其餘 46 項）。包括八種 320–900dp、短視窗、深淺色、1–2 倍字體版面，以及 Speak-to-Chat 操作／外部同步、EQ 上滑增益／自動自訂／等待回讀／失敗恢復／重連狀態、Widget、通知及離線啟動。滑桿觸控寬度至少 48dp，依可視高度縮放，六段維持由左至右排列，空間不足時橫向捲動。

Android 37 預覽映像的首次 instrumentation 因 Espresso 使用的 `InputManager.getInstance` API 不相容而中止，未列為通過；驗證使用 Android 36。測試證據與交付檔分別置於忽略的 `evidence/release-0.15.0`、`deliverables`。

實體 WF-1000XM5 的無線寫入、聽感、音訊並行與原手機驗收未執行；synthetic 回讀及模擬器 UI 不代表硬體通過。重連後重新讀取耳機狀態，不重送未確認的 EQ 目標；實機項目見 [清單](DEVICE_TEST.md)。

## v0.10.0 公開來源歷史建置驗證

公開來源的建置入口為 Gradle Wrapper 與 `tools/build.ps1`，版本與依賴見 [README](../README.md)。驗證命令：

```powershell
./tools/build.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
```

JVM 測試檢查 framing、能力與狀態解析、transport／Session 邏輯、有限選路及控制回讀。Lint 為靜態檢查；Android 測試 APK 建置僅確認測試可編譯與封裝，執行需額外的模擬器或測試裝置。

## 公開來源建置驗證

Windows／JDK 21.0.11、SDK Platform 36／Build Tools 36.0.0：由 Git 暫存內容匯出獨立目錄，未帶入原 `.local`、金鑰、`local.properties` 或建置輸出。使用既有公開依賴下載快取，停用 Gradle build cache 與 configuration cache，85 個任務全部執行成功；不是空白下載快取的首次連網安裝測試。

| 項目 | 結果 |
|---|---|
| `testDebugUnitTest` | 94 tests，0 failures／errors |
| `lintDebug` | 0 errors，44 warnings |
| `assembleDebug` | 成功，首次產生獨立 Debug 金鑰 |
| `assembleDebugAndroidTest` | 成功 |
| Android instrumentation／實機 | 此次未執行 |

原簽署金鑰及本機交付 APK 不作為此建置的輸入。原始碼檔案保留原內容，Lint 與測試 API 棄用警告未在此整理範圍內修改。

公開來源應能在不帶原開發者 `.local`、金鑰、`local.properties`、封存或建置產物的環境完成上述命令。首次建置會取得公開依賴並產生新的 Debug 金鑰，不要求取得原簽章。APK 簽章及二進位雜湊不保證與本機交付版相同。

本機原始測試紀錄及歷史 APK 不納入 Git。模擬器、synthetic transport 與靜態檢查不等同實體耳機驗收；v0.10.0 的實體 WF-1000XM5、原手機、LE Audio／LC3 並行與 OEM Launcher 仍待 [實機確認](DEVICE_TEST.md)。
