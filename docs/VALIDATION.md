# v0.10.0 驗證範圍

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
