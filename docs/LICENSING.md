# 授權與第三方來源

SoundDeck 自身未指定開源授權；本儲存庫沒有授予整個專案的開源 LICENSE。第三方的 MIT／Apache 告知只適用各自材料，不能視為對 SoundDeck 全體的授權。

## 隨原始碼散布的第三方材料

| 材料 | 來源與授權 | 保留方式 |
|---|---|---|
| Sony MDR 協定實作參考 | [SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient/blob/6f6101fd35454a95edad4d263cf7f7999ac55b77/LICENSE)，MIT | 保留完整著作權與 MIT 文字於根目錄告知及 `app/src/main/assets/sony-headphones-client-MIT.txt`；APK 告知仍保留 |
| Gradle Wrapper scripts／JAR | [Gradle 9.5.0](https://github.com/gradle/gradle/tree/v9.5.0)，Apache-2.0 | scripts 原始標頭及 `gradle/licenses/gradle-LICENSE.txt`、`gradle-NOTICE.txt`；未修改 Wrapper 程式 |

第三方授權文字見 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。協定與來源映射見 [PROTOCOL.md](PROTOCOL.md)。外部參考 checkout、歷史封包擷取與反編譯來源不隨本儲存庫散布；Gadgetbridge 只作交叉查閱，不作可直接納入的 MIT 來源。

## 建置時下載的依賴

AndroidX／Compose、Kotlin／kotlinx.coroutines 主要採 Apache-2.0；JUnit 4 測試依賴採 EPL-1.0。這些套件的 binary、source、SDK 及整個 Gradle distribution 不封存進 Git，建置由官方套件庫取得。實際傳遞依賴與其授權應以固定版本的 POM、套件內 LICENSE／NOTICE 為準；第三方著作權、告知及不同授權不得移除。

若另行散布 APK、測試 APK、SDK 或工具套件，需要依實際包含的依賴保留適用授權與 NOTICE，並另行確認簽署及發行條件。本原始碼準備工作不構成 GitHub Release 或正式二進位發行。Sony、WF-1000XM5 等名稱不代表取得商標授權或官方背書。
