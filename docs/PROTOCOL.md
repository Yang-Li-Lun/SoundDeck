# 協定依據與範圍

SoundDeck v0.10.0 僅採用下列可追溯的 MDR v2 Table 1 格式；官方功能清單不等於公開控制協定。

## 來源

1. [SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient)，MIT。固定檢視 commit `6f6101fd35454a95edad4d263cf7f7999ac55b77`。
   - [`libmdr/include/mdr-c/Base.h`](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/libmdr/include/mdr-c/Base.h)：MDR v2 RFCOMM SDP service UUID。
   - [`Command.cpp`](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/libmdr/src/Command.cpp)、`Command.hpp`、`Headphones.cpp`：framing、escape、checksum、ACK 與序號。
   - [`ProtocolV2.hpp`](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/libmdr/include/mdr/ProtocolV2.hpp)、`ProtocolV2T1.hpp`、`Generated/ProtocolV2T1Serialization.cpp`：能力編號、wire layout、長度前綴。
   - [`HeadphonesV2.cpp`](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/libmdr/src/HeadphonesV2.cpp)、`HeadphonesV2T1.cpp`：初始化、控制與狀態映射。
   - [`WF-1000XM5.md`](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/docs/device-support/WF-1000XM5.md)：上游裝置支援紀錄。這是上游證據，不是 SoundDeck 實機證據。
2. [Gadgetbridge Sony 頁面](https://gadgetbridge.org/gadgets/headphones/sony/)與 [SonyHeadphonesSupport.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesSupport.java)、[SonyProtocolImplV2.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/v2/SonyProtocolImplV2.java)：交叉確認 transport 與環境聲音格式。Gadgetbridge 為 AGPLv3，依本專案來源紀錄僅供交叉查閱，未作為移植來源；其程式不隨本儲存庫散布。SoundDeck 的關閉操作必須回讀確認，實際可用性不能先行宣稱。
3. Sony [WF-1000XM5 LE Audio 設定](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1001106279.html)、[支援編碼](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781984.html)：音訊路徑與控制路徑是不同問題。官方文件沒有在此提供公開的 Sony 專屬控制 API。
4. Android [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[前景服務類型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、[背景啟動限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：選擇已配對裝置、`BLUETOOTH_CONNECT`、使用者觸發 `connectedDevice` 前景服務。

另檢視 ohm-app 的 CC0 協定文件 commit `e38bf35c19e219ced93dd748ac6b48c2c4fe6ffd`；該文件在 RFCOMM 底層仍有未知欄位，不作本版 MDR payload 的依據。

## 採用的通訊

公開 `BluetoothDevice.createRfcommSocketToServiceRecord`，使用 MDR v2 service UUID `956C7B26-D49A-4BA8-B03F-B17D393CB6E2`。這是 **SDP／RFCOMM UUID，不是 BLE GATT characteristic**。沒有使用舊版 v1 UUID、隱藏 API 或固定 RFCOMM channel。

BLE 使用精確 Sony MDR service／TX／RX UUID，公開 Android GATT API，沿用下列封包與回讀機制。端點來源、選路、安全限制與未驗證邊界詳見 [LE_AUDIO.md](LE_AUDIO.md)。

封包：`3E | escape(type, seq, length[4-byte big endian], payload, checksum) | 3C`。checksum 為 header 與 payload 的無符號位元組加總低 8 bits；`3C/3D/3E` escape 成 `3D 2C/2D/2E`。Table 1 type `0C`，ACK type `01`。ACK 使用收到序號的反值；只有有效 ACK 才前進送出序號。解析器限制 payload 4096 bytes，驗證長度、序號、escape 與校驗。

初始化讀取 protocol info `00 00`、model `04 01`、firmware `04 02`、functions `06 00`。只接受已登錄的 WF-1000XM5 與回報 Table 1 可用的 v2 格式。能力清單是 count + `(function, priority)`，不是平面 bitmask。

| 功能 | 宣告能力 | GET / SET / RET / NTFY |
|---|---|---|
| 降噪／環境聲音 | `6B` | `66/68/67/69`, subtype `17`, 七位元組格式 |
| 左右耳／盒電量 | `21/22` 或 `29/2A` | `22/—/23/25`, subtype `01/02` 或 `09/0A`；百分比、充電狀態及 threshold 分別解析 |
| EQ | `50` | `56/58/57/59`, subtype `00`；`50/51` 能力、`52/53/55` 可用狀態 |
| DSEE | `E2` | `E6/E8/E7/E9`, subtype `01`；`E2/E3/E5` 可用狀態 |
| Speak-to-Chat Type 2 | `FC` | `F6/F8/F7/F9`, subtype `0C` |
| Speak-to-Chat 偵測靈敏度 | `FC` | `FA/FC/FB/FD`, subtype `0C`，四位元組延伸參數 |
| 音訊編碼顯示 | `12` | `12/—/13/15`, subtype `02` |

Sony 的布林值並不統一：DSEE Auto 為 `01`，Speak-to-Chat On 為 `00`。環境強度只有環境聲音模式可調，無虛構 ANC 強度。EQ 六段值依上游映射以 10 為中點；不支援格式不提供自訂滑桿。電量 `0` 是有效值，超出 0–100 或未知讀數不轉成 0；unknown charging 也保留未知。

所有控制先確認能力，必要時重新讀取其他欄位，寫入後 GET 回讀比較。ACK 只代表收到封包；不保證設定生效。拒絕、不同回報、逾時、未知格式均不報成功。損壞封包丟棄，同一 session 累計超過八個無效封包則關閉連線；寫入不盲目重試。

## 前景連線恢復與回饋

沿用成功端點紀錄與前景單次自動連線。配對清單以實際位址比對「上次成功控制」及通道；記錄編號只是顯示順序，選擇裝置不會覆寫成功紀錄。手動選擇與進階通道設定保留。

只有已通過完整 MDR 驗證的連線，在 App 前景收到實際 GATT `STATE_DISCONNECTED` 且 status 為 `0` 或 `0x08` 時，才允許有限恢復。這兩個值只在連線狀態 callback 解讀；GATT 讀寫操作的相同數值不視為連線逾時。每次連線工作最多兩次恢復，分別等待 1 秒、3 秒；即使短暫恢復成功，額度也不重設。舊 Session／reader／GATT 必須先關閉，再以相同裝置與通道重新讀取身分、能力及狀態。每個 Session 使用新指令佇列，不重播已排隊或已送出的設定。

手動斷線、開始手動選擇、離開前景與藍牙關閉會取消恢復等待或恢復握手。首次連線失敗、設定操作中斷而結果不明、協定錯誤、服務失效、權限錯誤、遠端／本機主動結束及未知 GATT 狀態不自動恢復。RFCOMM 的 EOF／一般 I/O 例外缺少可靠原因分類，維持停止控制並由使用者重新連線。

介面區分「連線失敗」、「控制連線中斷」、「設定尚未確認」及「耳機回報與要求不同」。前兩者提供重新連線入口；仍有控制連線的未確認／不符回報提供只讀的重新整理。診斷保留有限恢復結果、設定目標與實際回讀欄位；不記錄完整藍牙位址。BLE 控制連線不作為 LC3 音訊證據。

設計參考 [Nordic BLE Library 的有限重試與取消](https://github.com/nordicsemi/Android-BLE-Library/blob/main/ble/src/main/java/no/nordicsemi/android/ble/ConnectRequest.java)、[斷線分類](https://github.com/nordicsemi/Android-BLE-Library/blob/main/ble/src/main/java/no/nordicsemi/android/ble/BleManagerHandler.java)，以及 [Sony Device Center 的生命週期與寫入驗證](https://github.com/marconvcm/sony-device-center/blob/main/docs/ipc-and-lifecycle.md)。不引入其依賴、持續背景重連或無限重試，也不變更 Sony MDR payload。這些參考不是本專案的 WF-1000XM5 實機驗收。

## 聚焦人聲與語音偵測靈敏度

App 聚焦人聲使用 `6B / subtype 17`。切換前先 `66 17` 取得最新回覆，只接受 settled 且 Ambient，再發 `68 17`，保留當前強度與模式，最後 `66 17` 比對完整狀態。Widget 不提供聚焦人聲開關。外部已切 ANC／Off 時拒絕操作，無額外連線。

靈敏度依固定上游 commit `6f6101fd35454a95edad4d263cf7f7999ac55b77`：

- `ProtocolV2.hpp`：`SMART_TALKING_MODE_TYPE2 = FC`。
- `ProtocolV2T1.hpp`：`DetectSensitivity` 為 `00 AUTO`、`01 HIGH`、`02 LOW`；`SystemGetExtParam` 為 `FA type`，Type 2 的 RET／SET／NTFY 為 `FB/FC/FD 0C sensitivity modeOutTime`。`ModeOutTime` 只接受 `00..03`，SoundDeck 不提供結束時間編輯。
- `HeadphonesV2.cpp` 的初始化只在宣告 Type 2 時讀取延伸參數；寫入使用 `SystemSetExtParamSmartTalkingModeType2`。`HeadphonesV2T1.cpp` 將延伸回覆的 sensitivity 與 modeOutTime 分別儲存。
- [上游 WF-1000XM5 6.1.0 擷取](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/tests/WF-1000XM5-6.1.0/mdr-packet-1785749665111-000085-rx.type-DATA_MDR-0c.seq-01.cmd-SYSTEM_RET_EXT_PARAM-fb.bin)：完整 frame `3E 0C 01 00 00 00 04 FB 0C 00 00 18 3C`，payload `FB 0C 00 00`，證實該上游耳機曾回報 AUTO。這是上游 RX 證據，不是本版寫入驗收。
- Sony [Speak-to-Chat 說明](https://helpguide.sony.net/mdr/2963/v1/zh-tw/contents/TP1000783392.html) 說明高／低靈敏度及可調整語音偵測，但沒有提供 wire format。

App 只在 `FC` 能力及有效回報後啟用三個已知選項。沒有本機預設，也不從 `preview` 推導靈敏度。每次操作為 `FA 0C` → `FC 0C requested freshTimeout` → `FA 0C`，靈敏度與保留的 timeout 都相符才成功；不送 `F8`，不變更 Speak-to-Chat 開關或 preview。`FD` 外部通知亦更新實際狀態。未知 subtype、長度、列舉值均拒絕；逾時或不支援回覆沿用 Session 停止控制的政策。

## 回讀提示、切換狀態與被動診斷

回讀比較相符時，介面顯示「耳機回報設定相符」。此結果只表示該次回覆符合既有欄位比較規則，不證明聲學效果、官方 App 狀態或後續回報相同。一般模式操作只比較模式與使用者指定的 level／voice；聚焦人聲比較完整 NoiseState。DSEE Auto 設定不等於效果正在運作。

`noiseSettled` 保留最近一次 `67/69` 的 `ValueChangeStatus`：`CHANGED=01` 為 true，`UNDER_CHANGING=00` 為 false，尚未回報為 null。切換中的資料保留供診斷，但不作為已確認模式或開放降噪控制的依據；App、Widget、通知與 Tile 顯示切換狀態。降噪、環境刻度與聚焦人聲寫入前，必須取得本次 GET 的 settled 回覆，且沒有被較晚的不同／切換中通知取代。未通過即停止寫入，沒有等待、輪詢或重送。回讀後若已收到較晚的切換中／不同狀態，不發出相符提示。

被動紀錄附有程序內 Session `sid`、操作 `op`、請求 `req`、事件與 frame 編號、牆鐘毫秒及 Session 單調經過毫秒。`op=0` 表示尚未進入控制操作的初始化／閒置資料；`CONTROL_END completed` 只表示呼叫完成，設定結果仍看 `CONTROL … readback … matches`。TX 區分開始與 transportComplete；RX 保留 type、序號、長度、command、subtype，以及 ACK 預期／實收／完成結果、解析、去重、狀態更新與回覆配對。`replyForReq` 標示實際配對的請求，與 RX 到達時的 `req` 分開；這些編號不加入 wire payload，也不改變現有配對或序號規則。checksum／長度等被 decoder 拒絕時只記錄計數。

原始 payload 僅允許精確 command／subtype／長度的降噪、DSEE、Speak-to-Chat（含 preview／延伸參數）、codec／DSEE effect、固定訊息 alert 與電量格式；其他 payload、未知長度與 Table 2 內容遮蔽，只保留封包標頭資訊。未新增 effect／alert 查詢、訂閱或回覆，也不變更任何設定封包。診斷輸出例外不影響 Session。

診斷在記憶體最多保留 1024 行，每行最多 512 字元，匯出標示淘汰行數。每項實機操作後應立即匯出，包含操作前 GET、SET、ACK、操作後 GET 及期間通知；DSEE 需要 `E3/E5` 可用狀態，Speak-to-Chat 需要 `F7/F9` 的 preview。匯出同時附目前 settled、DSEE 可用性及 Speak 設定。關聯編號及相對時間只用於還原觀察順序，不能證明延遲 RET 屬於哪個實際耳機操作，也不能代替同時間官方 App 與實際效果的對照。

## 尚未證實的邊界

- 本版沒有實體耳機連線；聚焦人聲、三種靈敏度寫入與原功能均待實機回歸。
- LE Audio／LC3、音訊與控制並行、Sound Connect 共存、multipoint 與各廠背景限制均待實機。
- 上游擷取與本機模擬測試不等於本版全部功能、長期穩定性及音訊並行已驗證。觸控配置、其他 Sony 型號尚未支援。
- 未實作強制接管另一個控制 App、韌體更新、重設、解除配對、位置感知或雲端同步。

