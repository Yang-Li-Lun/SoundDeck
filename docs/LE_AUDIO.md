# LE Audio 與控制通道

SoundDeck v0.10.0 實作 Sony MDR v2 的 BLE GATT 與 Classic RFCOMM 控制路徑。LC3／LE Audio 是 Android 管理的音訊路徑；控制握手成功不能證明 LC3 播放中，設定寫入也必須另行回讀。

## 精確端點

| 用途 | UUID |
|---|---|
| MDR BLE service | `5b833e20-6bc7-4802-8e9a-723ceca4bd8f` |
| TX（HPC_TO_ACC） | `5b833c60-6bc7-4802-8e9a-723ceca4bd8f` |
| RX（HPC_FROM_ACC） | `5b833c61-6bc7-4802-8e9a-723ceca4bd8f` |
| 標準 CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| MDR v2 RFCOMM SDP service | `956c7b26-d49a-4ba8-b03f-b17d393cb6e2` |

必須找到唯一精確 service、TX、RX 與 CCCD，並核對 write／notify 或 indicate 屬性。不使用 UUID 前綴、任意可寫特徵或猜測的固定 RFCOMM channel。GATT 操作序列化，frame 以最多 20 bytes 分段，具時限及資源清理；不要求變更 MTU、PHY 或音訊 profile。

## 選路與資料

只列出名稱為 WF-1000XM5 或 LE_WF-1000XM5 的已配對候選，實際身分仍由 MDR 型號與能力確認。改名的耳機可能無法列出；名稱不是認證依據。App 不掃描、不建立或刪除配對。配對清單畫面顯示位址末兩組協助辨識；診斷匯出不含該末碼、完整位址、配對金鑰或音訊。

自動選路對 CLASSIC 使用 RFCOMM、LE 使用 BLE；DUAL／UNKNOWN 依 cached UUID 決定先後，cached UUID 不完整不能證明服務不存在。同一記錄初始化失敗可有限嘗試另一通道；權限、身分不符或取消則停止。手動指定通道不備援；已連線後錯誤不自動換通道或重播寫入。

所有入口共用單一 Repository／Controller／Session。Android GATT callback 或 MDR ACK 都不能代替耳機狀態回讀。未知 codec 不填成 LC3，電量不跨連線沿用。

## 來源與驗證邊界

BLE service 與 RFCOMM UUID、MDR framing 參考 MIT [SonyHeadphonesClient 固定版本](https://github.com/mos9527/SonyHeadphonesClient/tree/6f6101fd35454a95edad4d263cf7f7999ac55b77)，尤其 `libmdr/include/mdr-c/Base.h` 及 `libmdr-bt/src/Windows/ConnectionWindowsBLE.cpp`；此為上游 Windows 實作依據，不表示上游已驗證 Android。

TX／RX 名稱與精確 UUID 另與[歷史 Sony 列舉](https://github.com/egaebel/sony-headphones-hack/blob/ac1d5f8ff8d82a07e7b07d07ecc67c7b37a7b1ed/base-dex2jar.jar.src--files/com/sony/songpal/ble/client/CharacteristicUuid.java)核對。這些識別值不構成該實作程式碼的授權；儲存庫未納入其來源副本。通訊格式見 [PROTOCOL.md](PROTOCOL.md)。

v0.10.0 尚未完成實體 WF-1000XM5、LC3 音訊並行、Sound Connect 共存或 OEM 背景行為驗收。synthetic transport 只驗證本機邏輯；不證明 Android 藍牙堆疊或耳機必然配合。實機測試見 [DEVICE_TEST.md](DEVICE_TEST.md)；不可由單次 RFCOMM 斷線推論所有 LE Audio 情境都不支援 RFCOMM。
