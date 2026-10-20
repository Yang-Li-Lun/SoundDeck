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

## 聚焦人聲與語音偵測靈敏度

App 聚焦人聲使用 `6B / subtype 17`。切換前先 `66 17` 取得最新回覆，只接受 settled 且 Ambient，再發 `68 17`，保留當前強度與模式，最後 `66 17` 比對完整狀態。Widget 不提供聚焦人聲開關。外部已切 ANC／Off 時拒絕操作，無額外連線。

靈敏度依固定上游 commit `6f6101fd35454a95edad4d263cf7f7999ac55b77`：

- `ProtocolV2.hpp`：`SMART_TALKING_MODE_TYPE2 = FC`。
- `ProtocolV2T1.hpp`：`DetectSensitivity` 為 `00 AUTO`、`01 HIGH`、`02 LOW`；`SystemGetExtParam` 為 `FA type`，Type 2 的 RET／SET／NTFY 為 `FB/FC/FD 0C sensitivity modeOutTime`。`ModeOutTime` 只接受 `00..03`，SoundDeck 不提供結束時間編輯。
- `HeadphonesV2.cpp` 的初始化只在宣告 Type 2 時讀取延伸參數；寫入使用 `SystemSetExtParamSmartTalkingModeType2`。`HeadphonesV2T1.cpp` 將延伸回覆的 sensitivity 與 modeOutTime 分別儲存。
- [上游 WF-1000XM5 6.1.0 擷取](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/tests/WF-1000XM5-6.1.0/mdr-packet-1785749665111-000085-rx.type-DATA_MDR-0c.seq-01.cmd-SYSTEM_RET_EXT_PARAM-fb.bin)：完整 frame `3E 0C 01 00 00 00 04 FB 0C 00 00 18 3C`，payload `FB 0C 00 00`，證實該上游耳機曾回報 AUTO。這是上游 RX 證據，不是本版寫入驗收。
- Sony [Speak-to-Chat 說明](https://helpguide.sony.net/mdr/2963/v1/zh-tw/contents/TP1000783392.html) 說明高／低靈敏度及可調整語音偵測，但沒有提供 wire format。

App 只在 `FC` 能力及有效回報後啟用三個已知選項。沒有本機預設，也不從 `preview` 推導靈敏度。每次操作為 `FA 0C` → `FC 0C requested freshTimeout` → `FA 0C`，靈敏度與保留的 timeout 都相符才成功；不送 `F8`，不變更 Speak-to-Chat 開關或 preview。`FD` 外部通知亦更新實際狀態。未知 subtype、長度、列舉值均拒絕；逾時或不支援回覆沿用 Session 停止控制的政策。

## 尚未證實的邊界

- 本版沒有實體耳機連線；聚焦人聲、三種靈敏度寫入與原功能均待實機回歸。
- LE Audio／LC3、音訊與控制並行、Sound Connect 共存、multipoint 與各廠背景限制均待實機。
- 上游擷取與本機模擬測試不等於本版全部功能、長期穩定性及音訊並行已驗證。觸控配置、其他 Sony 型號尚未支援。
- 未實作強制接管另一個控制 App、韌體更新、重設、解除配對、位置感知或雲端同步。

