# LE Audio 與控制通道

SoundDeck 實作 Sony MDR v2 的 BLE GATT 與 Classic RFCOMM 控制路徑。LC3／LE Audio 是 Android 管理的音訊路徑；控制握手成功不能證明 LC3 播放中，設定寫入也必須另行回讀。回讀相符不證明聲學效果或官方 App 狀態已改變。

## 精確端點

| 用途 | UUID |
|---|---|
| MDR BLE service | `5b833e20-6bc7-4802-8e9a-723ceca4bd8f` |
| TX（HPC_TO_ACC） | `5b833c60-6bc7-4802-8e9a-723ceca4bd8f` |
| RX（HPC_FROM_ACC） | `5b833c61-6bc7-4802-8e9a-723ceca4bd8f` |
| 標準 CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| MDR v2 RFCOMM SDP service | `956c7b26-d49a-4ba8-b03f-b17d393cb6e2` |

必須找到唯一精確 service、TX、RX 與 CCCD，並核對 write／notify 或 indicate 屬性。不使用 UUID 前綴、任意可寫特徵或猜測的固定 RFCOMM channel。GATT 操作序列化，frame 以最多 20 bytes 分段，具時限及資源清理；不要求變更 MTU、PHY 或音訊 profile。

診斷候選 `0.14.0-diagnostic` 被動記錄 `GATT_CONFIG` 的實際 write type、notify／indicate 選擇及固定 20-byte 分段限制；`GATT_MTU_CALLBACK` 記錄目前 GATT instance 收到的公開 `onMtuChanged` 參數、status 與當下 pending stage。沒有呼叫 `requestMtu`、新增特徵讀取／訂閱或等待 MTU；callback 成功或失敗都不完成 pending 操作、不變更分段／握手／錯誤處理。沒有 callback 只表示本次未觀察到，不能填成 MTU 23 或推論握手失敗；callback 也不證明由哪個 App 或系統流程觸發。關閉後的事件忽略，新增診斷輸出例外不影響控制。

## 選路與資料

只列出名稱為 WF-1000XM5 或 LE_WF-1000XM5 的已配對候選，實際身分仍由 MDR 型號與能力確認。改名的耳機可能無法列出；名稱不是認證依據。App 不掃描、不建立或刪除配對。配對清單畫面顯示位址末兩組協助辨識；診斷匯出不含該末碼、完整位址、配對金鑰或音訊。

自動選路對 CLASSIC 使用 RFCOMM、LE 使用 BLE；DUAL／UNKNOWN 依 cached UUID 決定先後，cached UUID 不完整不能證明服務不存在。同一記錄初始化失敗可有限嘗試另一通道；權限、身分不符或取消則停止。手動指定通道不備援；已連線後錯誤不自動換通道或重播寫入。

所有入口共用單一 Repository／Controller／Session。Android GATT callback 或 MDR ACK 都不能代替耳機狀態回讀。未知 codec 不填成 LC3，電量不跨連線沿用。

成功端點快取只有完整位址（留在既有本機 preferences）與通道，在型號、能力與初始狀態讀取完成後更新；不含 codec、LE Audio 群組或聲學驗證。前景自動連線使用該成功端點與通道，手動 AUTO 依當次記錄與 cached UUID 選路。記錄編號是當次排序，不能獨自當作跨次連線的端點識別。這些規則不因診斷結果改變。

## 唯讀音訊端點快照

每個 MDR Session 建立時，獨立診斷工作各讀取一次 A2DP 及（Android 13／API 33 起）LE Audio profile。只使用公開 `getProfileProxy`、`getConnectedDevices`、`BluetoothLeAudio.getGroupId`、`closeProfileProxy`；沿用現有藍牙權限。沒有音訊 profile connect/disconnect、active-device 設定、掃描、配對、反射或隱藏 API。profile proxy 是查詢介面，其釋放不要求斷開耳機。

- `CONTROL_ENDPOINT`：與既有 MDR `sid` 關聯，包含實際選定的匿名端點、當次記錄、radio、偏好／實際通道、自動連線旗標及是否與上次成功端點同位址。驗證完成另記 `codecReport`，來源是既有 MDR 回報。
- `AUDIO_ENDPOINT`：包含 SDK、profile、查詢結果、耗時、連線清單的匿名端點與群組，以及選定端點的群組。`same_address_connected` 僅表示 API 清單包含相同 Android BluetoothDevice 位址；`same_group_connected` 僅表示有效群組 ID 相同。其餘 `relation=unknown`，不推論選錯端點、另一隻耳機或缺少控制角色。
- 匿名代號只在同一 `aliasScope`（Repository 生命週期）內可比較；不輸出完整或尾碼位址、裝置名稱、序號或可持續追蹤的位址雜湊。App 重啟後代號不可直接比較；`sameAsLastVerified` 仍可比對既有成功快取，但不含該快取當時的音訊模式。
- API 不支援、proxy 無法取得、1.5 秒內未收到 proxy callback、權限拒絕、服務中斷及讀取失敗分別記錄原因；無法取得的群組為 `unknown`，不讓兩個無效群組形成相符。已取得及遲到的 proxy 都會嘗試釋放，取消不會變成控制連線失敗。
- 此為非原子、一次性的連線階段快照；沒有持續監聽或輪詢。profile 清單不是正在播放的證明，A2DP 不等於 AAC，LE Audio 清單也不單獨證明當時 LC3 已在傳送。公開查詢可能在系統服務不可用時回傳空清單／無效群組，空值只作 API 觀察。`activeAudio=unknown` 與 `acousticEffect=unknown` 保留此界線。
- 1.5 秒只限制等待 proxy callback；平台同步 Binder getter 受 Android 實作影響，不能保證同樣時限。查詢在 IO 工作執行，MDR 不等待診斷完成；Session 結束即取消診斷，診斷例外不改變控制流程。中途切換音訊模式後，原快照不代表新狀態，需新的控制 Session 才有新快照。

API 依據：[Android BluetoothLeAudio](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio)、[Android 16 公開實作](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android16-release/framework/java/android/bluetooth/BluetoothLeAudio.java)。編譯與 Lint 使用本機 Android SDK 36；SDK 26–32 保留 A2DP 查詢、LE Audio 標示不支援，不新增權限。

## MDR 對照與目前未解問題

目前使用者提供的 WF-1000XM5 實機證據如下；本機沒有操作耳機或手機，也尚未取得能逐 frame 重建、條件一致的成對原始匯出。

- v0.13.0 在 LC3 下透過 BLE GATT 完成 MDR 初始化與 ACK。Android 回報兩個 LE Audio 裝置同屬 Group 1，選定控制端點也位於該群組。群組關係已被觀察到，但不證明其中任何成員具有會套用設定的控制角色。
- 降噪／環境音回讀 `matches=true`，實際聲學效果沒有同步；DSEE 關閉 `E8 01 00` 有 ACK，但 `E9` 通知及 `E7` 回讀仍為開啟。另有 Speak-to-Chat 回讀不符的觀察；不能把這三種結果全部視為相同的回讀成功。
- AAC／RFCOMM 控制正常；這次 LC3／RFCOMM 無法連線。早期 LC3 曾可控制，v0.10.0 也曾在沒有修改程式的情況下短暫恢復。版本、端點、連線時序及耳機內部狀態的因果關係尚未建立。
- 已排除官方 App 同時執行。同一手機／LC3 模式下，使用者已手動指定 BLE 測過兩筆已配對記錄，兩筆都無法切換實際降噪／環境音效果；結束 SoundDeck 控制後，Sony Sound Connect 則可正常切換可聽效果。這縮小至 App 控制實作／Session 條件差異，但尚未識別是哪個差異，不能繼續把單純更換配對記錄當作待測修復。

根因維持 **UNRESOLVED**。目前保留既有選路、初始化、payload、ACK、回讀、有限恢復及設定保存行為；不因群組相同、初始化成功或回讀相符而自動改選另一個端點。

目前 Session 串行處理 request，ACK 檢查反向序號，RET 依 Table 1、command 與 subtype 配對；NTFY 可以更新狀態但不代替 RET。既有診斷已記錄 ACK、`replyForReq`、去重、解析、狀態套用與控制回讀。既有 synthetic 測試涵蓋錯誤 ACK、RET 早於 ACK、通知交錯、重複 frame、損壞 frame 與逾時。這些測試不能證明實機沒有延遲／錯配：wire 沒有本機 `req` 識別碼，同 command／subtype 的延遲 RET 來源仍需封包時序證據。

固定上游 `6f6101fd35454a95edad4d263cf7f7999ac55b77` 的 `Base.h`、`Headphones.cpp`、`HeadphonesV2.cpp`、`HeadphonesV2T1.cpp` 與 Windows BLE transport 顯示：BLE 是傳輸選項；降噪、DSEE 與 Type 2 Speak 控制共用 MDR v2 Table 1，檢視的控制實作沒有依 LC3 選用另一套 payload 或必要握手的分支。上游支援表未區分 AAC／LC3，不能視為 LC3 驗收。

仍須區分的實作差異：上游初始化讀取較多 capability、依能力啟用固定 alert，Speak SET 將 preview 設為 OFF；SoundDeck 使用已支援的初始化子集並保留新鮮讀得的 preview。上游 Windows BLE 亦有不同的 characteristic 選取／write type 策略。沒有證據證明這些差異造成目前症狀或是 LC3 專用要求，因此不移植額外命令、不改 preview、不放寬精確 GATT UUID。現有 `E3/E5`、`F7/F9`、alert／codec 被動紀錄應先用於實機比對。

## SonyPods 差異與修正判斷

檢視日期：2026-10-24。SonyPods 僅作行為與技術原理比較；其 [GPL-3.0 原始碼](https://github.com/Mercury000/SonyPods/blob/3f701ff8f9b30c6909b9dbe8d99248f20d55560e/LICENSE)不複製至 SoundDeck，也不作為可直接移植的 MIT 來源。以下區分上游作者的診斷、使用者回報與實際程式分支，不把上游註解當作本機根因證明。

[Issue #9](https://github.com/Mercury000/SonyPods/issues/9) 的實機成功回報指向 **Build #88／`fe87e1e1224c45a64a126bf4c0c179e554a6fd07`**，內容為 LE Audio 核心功能恢復，沒有逐項提供降噪、DSEE、Speak-to-Chat 的聲學／設定驗收。該版本包含多項行為，不能把成功單獨歸因於某個端點修正；也不能將其當作之後 `920e494` 或目前版本的實機證明。

| 比較項目 | SonyPods 的可核對行為 | SoundDeck 現況與處置 |
|---|---|---|
| 歷史端點修正 | [`e98d2c0`](https://github.com/Mercury000/SonyPods/commit/e98d2c0b6274f1b1f08d9e3aa30dd4b9faa612e2) 註解描述特定平台 pure-LE 位址有回聲卻不生效、dual 位址可生效。實際分支是在 LE Audio 被判為已連線時保留 requested device，並非一律改選 DUAL。對非 null 的 requested 及同一連線判定值，修改前後的 target 條件結果相同；實質相關變更包含 `isLeAudioConnected` 的判定方式，不能只把註解當成位址切換 patch。 | `HeadphoneRepository.connect` 一直對使用者選取的 `chosen.address` 建立控制連線，沒有自動改成另一個 LE 位址。症狀相似支持做端點對照，但不足以新增「Group 1 的某一筆必定正確」規則。 |
| 最新端點解析 | 固定目前 HEAD [`3f701ff8…/TandemRouteResolver.kt`](https://github.com/Mercury000/SonyPods/blob/3f701ff8f9b30c6909b9dbe8d99248f20d55560e/app/src/main/java/dev/sonypods/ble/TandemRouteResolver.kt)：同一 topology 快照決定 transport 與位址；已有 control identity 時優先已連線的其他已知 LE identity，必要時等待；未知身分有原位址備援，耳機指定 migration 則另處理。 | 該 resolver 不以 Android group ID 選角色。SoundDeck 的公開 profile 快照只供診斷，不參與選路。不能從兩筆同群組補出 SonyPods 所需的身分方向，也不能直接移植此規則。 |
| 身分關聯的來源 | [`HeadsetRegistry.kt`](https://github.com/Mercury000/SonyPods/blob/3f701ff8f9b30c6909b9dbe8d99248f20d55560e/app/src/main/java/dev/sonypods/device/HeadsetRegistry.kt) 將耳機 capability identifier／LEA capability 位址與實際 SPP 或其配對流程所得的 control identity 分開保存。 | SoundDeck 成功快取只有位址與 transport，不具備相同身分資料。此次不增加私有 capability 命令、配對操作或持久化角色推測。 |
| ACK 卡住及初始化順序 | `e98d2c0` 把 protocol-info 移到 capability 之前，並移除 LE Audio alert enable；作者記錄這兩種錯誤序列可造成舊 ACK 重複。 | `HeadphoneRepository` 已以 protocol-info 為第一個 MDR request，且沒有送該 alert。使用者亦回報 ACK 正常；沒有已證實可套用的初始化缺口，不增加 alert 或重送。 |
| 單一可靠傳輸通道 | `e98d2c0` 讓沒有獨立 GATT endpoint 的其他 table 指令使用既有 framed session；[`920e494`](https://github.com/Mercury000/SonyPods/commit/920e494d3f9922aaf8dd5cffa47326d743407165) 將 GATT bearer 收斂至 v2 HPC。 | SoundDeck 已使用精確 v2 HPC service／TX／RX，四項控制均經同一 `MdrSession` 的 Table 1。沒有多 table 送到不存在通道的同類路徑。 |
| GATT 準備與寫入方式 | `920e494` 改為要求 MTU 517、有限的 DETERMINE_MTU probe、讀取 WRITABLE_VALUE_LENGTH，並延長 GATT write callback 時限。固定目前 HEAD 的 [`SonyBleClient.kt`](https://github.com/Mercury000/SonyPods/blob/3f701ff8f9b30c6909b9dbe8d99248f20d55560e/app/src/main/java/dev/sonypods/ble/SonyBleClient.kt) 又改為等待 Xiaomi automatic MTU callback；資料寫入使用 no-response。 | SoundDeck 不要求 MTU，最多 20 bytes 分段；TX 支援 WRITE 時用 with-response，否則才用 no-response。這些是真實差異，但 ACK／回讀正常不能證明或排除其影響。沒有相同手機、端點及條件的成功／失敗對照，不變更握手、write type 或時限。 |
| 四項設定格式 | 固定 HEAD 的 [`SonyTandemV2Table1Protocol.kt`](https://github.com/Mercury000/SonyPods/blob/3f701ff8f9b30c6909b9dbe8d99248f20d55560e/app/src/main/java/dev/sonypods/protocol/SonyTandemV2Table1Protocol.kt) 使用相同的 NC/ASM Table 1 結構、DSEE OFF=00／AUTO=01；Speak setter 的 preview 欄固定 01。檢視的 setters 沒有 LC3 專用 payload 分支。 | SoundDeck 的 `E8 01 00` 關閉編碼沒有因此被推翻；Speak 保留新鮮回報的 preview，這個差異不能解釋其他三項控制。保留既有 payload 與欄位保存規則，不藉機改寫 preview。 |

現有 MDR 紀錄沒有 Android MTU callback 觀察值，因此診斷候選只補足該既有事件及實際 GATT write type，供成功／失敗 Session 比較。它不讀取 WRITABLE_VALUE_LENGTH，也不能看見官方 App 的連線；不能把新增 GATT read、MTU request、CCCD 寫入或 MDR command 稱為被動診斷。這是取證候選，尚無已證實的 LC3 控制修正。

Sony [功能清單](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781980.html) 將部分功能標為 LE Audio 不可用，但降噪、DSEE 設定與 Speak-to-Chat 未列入該標記；這不保證每項操作在所有韌體／音訊情境都能執行，也不能據此新增協定。

## 下一項最有鑑別力的實機驗證

兩筆端點的失敗及官方 App 成功已由使用者確認，不要求重做同一項端點篩選。下一項應取得**同一手機／耳機／LC3 條件下，官方成功 Session 與 SoundDeck 失敗 Session 的 GATT／ATT 及 MDR 初始化對照**；目標是區分實際目標位址、握手／寫入方式及耳機 Session 狀態。這仍是待使用者執行的計畫，本機代理沒有實機操作授權。

1. 保持既有音訊模式、配對、群組、韌體、佩戴與播放條件，記下原控制選擇與功能設定。兩個 App 依序建立各自控制 Session，不同時連線；記錄先後及時間，不能把先前官方 App 留下的狀態當作 SoundDeck 設定生效。
2. 在一筆已確認可初始化的記錄上手動指定 BLE，使用診斷候選建立一次新 SoundDeck Session，初始化完成立即匯出。單次執行「降噪 → 環境音」對照，各設定只送一次並立即匯出，記錄可聽效果及播放有無中斷。不再次輪試兩筆，不變更音訊模式、不以反覆連線尋找偶發成功。
3. 比較 `GATT_CONFIG`、`GATT_MTU_CALLBACK` 的發生順序、初始化第一個 request、SET／ACK／RET／NTFY，以及官方成功 Session 的實際 GATT 目標、ATT write opcode、MTU 事件、CCCD 準備和第一個 SET 前的 MDR 往返。SoundDeck 匯出只能提供自己的部分；官方側須由使用者透過手機可用的公開診斷取得成對資料，分享前遮蔽位址、金鑰與無關流量。不提供這些資料的手機保留取證缺口，不使用 Root、隱藏 API、Xposed 或修改系統服務補足。
4. 只有對照能指出與失效一致的差異，才設計針對該差異的最小候選；不能僅因 MTU callback 有／無、WRITE 有回應／無回應不同便宣告原因。若新的 Session 意外恢復，保存該次診斷並標為短暫恢復，不宣稱被動紀錄修好了問題。

DSEE 與 Speak-to-Chat 仍要各自取證：前者核對 `E8 01 00`、`E3/E5` 可用性、`E7/E9` 設定與實際 effect 的差別；後者保留 `F7/F9` enabled／preview 及實際觸發觀察。一次 ANC 生效不能代表四項通過。測試完成恢復原功能設定；AAC 回歸待使用者原本使用 AAC 或另行授權時進行，與 LC3 四項功能及連線穩定性分別驗收。

每個設定操作後另存含 `CONTROL_BEGIN` 到回讀與後續通知的匯出，避免 1024 行緩衝淘汰端點快照。重點欄位：

1. `aliasScope/selected/route/sameAsLastVerified` 是否相同，及 LE Audio `selectedGroup`／連線群組能否建立關係。不同位址或未知群組不能單獨判定選錯端點。
2. MDR 型號、韌體、能力、codec 回報及初始化差異；保持「控制端點回報」與「手機音訊模式」為兩項證據。
3. 同一 `sid/op/req` 內的 SET、ACK、RET 與 NTFY 順序；降噪 `67/69` 的 settled 與後續反轉、DSEE `E3/E5/E7/E9`、Speak `F7/F9` 的 enabled／preview。`matches=true` 只代表欄位比較相符。

## 來源與驗證邊界

BLE service 與 RFCOMM UUID、MDR framing 參考 MIT [SonyHeadphonesClient 固定版本](https://github.com/mos9527/SonyHeadphonesClient/tree/6f6101fd35454a95edad4d263cf7f7999ac55b77)，尤其 `libmdr/include/mdr-c/Base.h` 及 `libmdr-bt/src/Windows/ConnectionWindowsBLE.cpp`；此為上游 Windows 實作依據，不表示上游已驗證 Android。

TX／RX 名稱與精確 UUID 另與[歷史 Sony 列舉](https://github.com/egaebel/sony-headphones-hack/blob/ac1d5f8ff8d82a07e7b07d07ecc67c7b37a7b1ed/base-dex2jar.jar.src--files/com/sony/songpal/ble/client/CharacteristicUuid.java)核對。這些識別值不構成該實作程式碼的授權；儲存庫未納入其來源副本。通訊格式見 [PROTOCOL.md](PROTOCOL.md)。

診斷候選尚未完成實體 WF-1000XM5、LC3 音訊並行、Sound Connect 共存或 OEM 背景行為驗收。synthetic transport 只驗證本機邏輯；不證明 Android 藍牙堆疊或耳機必然配合。實機測試見 [DEVICE_TEST.md](DEVICE_TEST.md)；不可由單次 RFCOMM 斷線推論所有 LE Audio 情境都不支援 RFCOMM。
