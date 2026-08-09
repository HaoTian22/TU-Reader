# NFC 交通卡读取器 · 第一版 (v0.1)

这是基于公开交通卡协议文档（NFC Wiki 智能卡手册）搭建的最小可用（MVP）Android 项目，
用于替代已停更的"读卡识途"App 的核心功能：读取交通卡余额与交易记录。

## 当前支持范围（第一版）

- 交通联合卡 (T-Union) 电子钱包应用
- 深圳通 (Shenzhen Tong)
- 数字城市一卡通 (City Union)

这三种卡共用相似的"电子钱包交易明细记录文件"结构（SFI=0x18 的循环记录文件），
所以第一版用同一套解析逻辑覆盖它们。北京市政一卡通(BMAC)、岭南通等文件结构不同的卡种
留作第二版扩展（代码里已经用 TODO 标注扩展点）。

## 如何搭建项目结构

在本地新建一个文件夹 `NFCTransitReader`，按下面的路径把我生成的各个文件放进去：

```
NFCTransitReader/
├── settings.gradle
├── build.gradle                    # 根项目配置
├── gradle.properties               (可选，内容见下方说明)
└── app/
    ├── build.gradle                # App 模块配置
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/nfctransit/
        │   ├── MainActivity.kt
        │   ├── ApduUtil.kt
        │   ├── CardProfile.kt
        │   └── TransitCardReader.kt
        ├── res/layout/activity_main.xml
        ├── res/values/strings.xml
        └── res/xml/nfc_tech_filter.xml
```

`gradle.properties` 内容（自己新建即可）：
```
android.useAndroidX=true
kotlin.code.style=official
```

## 如何运行

1. 用 Android Studio 打开 `NFCTransitReader` 目录（File → Open）。
2. 等待 Gradle Sync 完成（首次可能需要下载 Gradle 8.2 / Kotlin 1.9.10 依赖）。
3. 用支持 NFC 的真机（模拟器无法测试 NFC）连接调试，运行 App。
4. 打开手机 NFC 开关，运行后把交通卡贴近手机背面 NFC 感应区。
5. App 会尝试用已知 AID 逐一 SELECT，命中后读取信息文件和最近交易记录，
   并在界面上显示解析结果和原始 APDU 十六进制日志（方便你调试未识别的卡种）。

## 核心原理

1. **前台调度获取 Tag**：`NfcAdapter.enableForegroundDispatch` + `IsoDep` 技术过滤，
   只处理支持 ISO14443-4 的 CPU 卡（大多数城市交通卡都是这类卡）。
2. **SELECT 应用**：发送 `00 A4 04 00 <Lc> <AID> 00`，命中已知 AID 即认为识别出卡种
   （AID 列表来自 wiki.nfc.im 智能卡手册的"AID 索引"表）。
3. **读取文件**：
   - 公共应用信息文件（通常 SFI=0x15）：解析发卡机构、有效期等。
   - 电子钱包交易明细文件（通常 SFI=0x18，循环记录）：逐条 READ RECORD，
     直到卡片返回失败状态码为止，解析出交易序号、金额（分转元）、
     交易类型、终端编号、日期时间。
4. **金额字段**：4 字节 HEX 大端表示，单位为"分"，除以 100 得到元。
5. **日期时间字段**：BCD 编码，直接按 BCD 转字符串即可得到 YYYYMMDD / HHMMSS。

## 已知局限 / 下一步要做的事

- **字节偏移量是首版估计值**，不同卡种即使共用 0x18 结构，实际发卡方也可能有细微差异，
  需要你拿真实卡片测试，对照界面上打印的"原始 APDU 日志"，逐字段核对、修正偏移。
- 目前**没有站名映射表**（比如终端编号→地铁站名），这是读卡识途最有价值的功能之一，
  需要你自己整理各地地铁公司公开的"线路编号规则"，建议做成本地 JSON 表，
  按 城市代码 + 线路编号 + 站序 查表。
- **MIFARE Classic 卡（如八达通、部分老卡）未支持**，需要密钥才能读取，
  属于第二版工作，且涉及密钥获取的合规问题需要你自行评估。
- 未做异常卡片（低电量、连接中断）的重试与超时优化，属于体验优化范畴。
- 建议接下来用 Metrodroid 开源代码（GitHub: metrodroid/metrodroid）交叉验证你的解析结果，
  它是目前功能最全的开源对标项目。

## 数据来源

卡种 AID、SFI、文件结构均来自 NFC Wiki 智能卡手册（wiki.nfc.im/books/智能卡手册），
APDU 命令格式参考 ISO/IEC 7816-4 标准及 Android 官方 NFC/HCE 开发文档。
