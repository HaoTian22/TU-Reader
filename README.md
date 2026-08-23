# NFC 交通卡读取器

基于公开交通卡协议文档（NFC Wiki 智能卡手册）搭建的 Android NFC 交通卡读取应用，支持多种主流交通卡的余额、交易记录和统计数据读取。

**⚠️ 本项目 99% 由 AI 辅助开发（Vibe-Coding），基于公开协议文档实现。**

## 功能特性

### 支持的卡片类型
- 🚄 **交通联合卡 (T-Union)** — 全国互联互通卡，覆盖 300+ 城市
- 🚇 **深圳通 (SZT)** — 深圳市公共交通卡
- 🚌 **岭南通/羊城通 (LNT/YCT)** — 广东省互联互通卡（仅支持部分广州/佛山地铁，公交会识别错误）
- 🚉 **苏州通 (SUZ)** — 苏州市交通卡（未验证）
- 🚃 **天津城市卡 (TFT)** — 天津市公共交通卡（未验证）
- 🏙️ **住建部 CU (City Union)** — 城市一卡通系统（未验证）

### 可读取的信息
- **卡基本信息** — 卡号、发卡日期、有效期、发卡机构
- **余额查询** — 实时钱包余额
- **交易记录** — 最近乘车交易（时间、金额、站点、卡类型）
- **优惠统计** — 本月乘车次数、累计优惠金额
- **城市信息** — 发卡城市代码、线路信息

## 截图演示

<div align="center">
<img width="320" alt="主界面 - 卡片列表" src="https://github.com/user-attachments/assets/24c639c7-0086-455b-a588-ea10b1cb6009" />
<img width="320" alt="读取详情 - 交易记录" src="https://github.com/user-attachments/assets/860dece1-ab59-4caf-afa1-dfd8e8c1286e" />
<img width="320" alt="统计分析 - 乘车图表" src="https://github.com/user-attachments/assets/e6e1468e-2b22-40c7-b624-d9f726cd9bf8" />
<img width="320" alt="设置 - 数据库更新" src="https://github.com/user-attachments/assets/a4634852-ee96-46b3-81d7-013e2aee6c5b" />
</div>

## 快速开始

### 环境要求
- Android 10+ (API 29+)
- 支持 NFC 的 Android 设备
- NFC 功能已开启

### 安装使用

1. **下载 APK** — 从 [Releases](https://github.com/你的用户名/TU-Reader/releases) 下载最新版本
2. **安装并打开** — 授权 NFC 权限
3. **贴近卡片** — 将交通卡贴近手机背面 NFC 区域
4. **查看结果** — 自动识别卡类型并显示详细信息

### 使用场景
- 📊 查看交通卡余额和最近交易记录
- 📈 分析月度乘车次数和消费统计
- 💾 导出交易数据用于个人记账
- 🔍 排查异常扣费或交易问题

## 核心原理

本应用基于 NFC Wiki 智能卡手册（wiki.nfc.im/books/智能卡手册）的公开交通卡协议文档实现，支持 ISO/IEC 7816-4 标准的 APDU 命令交互，自动识别并读取多种交通卡的数据。

### 技术特点
- 🔍 **自动卡类型识别** — 根据 AID (Application Identifier) 自动判断卡类型
- 📱 **NFC-HCE 支持** — 基于 Android Host-based Card Emulation 实现
- 🔄 **在线数据库更新** — 支持远程更新站名映射表，无需重新安装
- 💾 **本地数据存储** — 使用 Room 数据库安全保存卡片信息

### 数据来源
- 卡种 AID、SFI、文件结构均来自 NFC Wiki 智能卡手册
- APDU 命令格式参考 ISO/IEC 7816-4 标准及 Android 官方 NFC/HCE 开发文档

---

## 开发者文档

### 构建项目

由于本机无 Gradle Wrapper 和 JAVA_HOME，使用以下命令构建：

```bash
export JAVA_HOME="C:/Users/Hao_T/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"
export PATH="$JAVA_HOME/bin:$PATH"
/c/Users/Hao_T/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle :app:assembleDebug --console=plain
```

### 腾讯地图 WebService SN 配置

公交路线规划和站点搜索使用腾讯地图 WebService SN 校验。本地开发时，在根目录下被 Git 忽略的 `local.properties` 中配置：

```properties
TENCENT_MAP_WEB_SERVICE_KEY=your-webservice-key
TENCENT_MAP_SECRET_KEY=your-secret-key
```

CI 构建使用同名环境变量 `TENCENT_MAP_WEB_SERVICE_KEY` 和 `TENCENT_MAP_SECRET_KEY`，环境变量会覆盖 `local.properties`。未配置任意一项时应用仍可构建，但不会发起腾讯 WebService 请求。

> ⚠️ 当前应用采用客户端直签，Key 与 SecretKey 会被编译进 APK。此方式只能避免凭据进入版本库，无法防止从 APK 中提取；面向不可信用户分发时应改为由后端保存 SecretKey 并代理请求。

### 数据库架构

应用使用两套独立的 SQLite 数据库：

| 数据库 | Room 版本 | 内容 | 存储位置 |
|---|---|---|---|
| `transit.db` | v2 (AppDatabase) | 城市/线路/站点/读卡器设备（含线路级实际地点） | assets/data/ → 应用私有目录 |
| `user_data.db` | v4 (UserDatabase) | 卡片/原始记录/交易归档 | 应用私有目录 |

**重要约束**：`AppDatabase` 服务端数据库必须与应用 schema 的 identity_hash 一致。当前 Room v2 identity hash 为 `54a2c8a30362af8a1d7aecd3d7d0f22f`。

界面构建结果和地图路线等可再生成数据不进入用户数据库，而是按 JSON 文件存放在应用的 `cacheDir`：

```text
cache/
├── ui_cache/
│   └── <card-id>.json
└── route_cache/
    ├── route_<sha256>.json
    └── station_<sha256>.json
```

缓存可由系统或设置页随时清除；导出 `user_data.db` 时不会包含这些文件。

### 在线更新机制

应用支持在运行时下载并替换 `transit.db` 数据库：

1. 从 `https://assets2.haotian22.top/transit.db` 下载新库
2. 校验 identity_hash 确保与当前 schema 兼容
3. 替换旧库并重建内存索引
4. 站点名称按界面语言即时解析

---

## 技术参考（APDU 协议）

<details>
<summary>展开查看完整的 APDU 命令和卡类型读取序列</summary>

### 一、卡片识别 SELECT 命令

| AID / 命令 | 命中即判定 |
|---|---|
| A00000000386980701 | 交通联合（代码日志"CU"） |
| A000000632010105 | 住建部 CPU（日志"TU"） |
| PAY.SZT | 深圳通 |
| SUXIN.DDF01 | 苏州（需 PIN） |
| SZTK_ZYY | 苏州 CIKA |
| PAY.APPY | 岭南通 YCT1 |
| PAY.TICL | 岭南通 YCT2 |
| 91560000 144D4F542E424D4143303031 | 北京 |
| D1560000 15B9ABB9B2D3A6D3C3 | 天津 |
| 00 A4 00 00 02 3F 00/01 | SELECT MF / 3F01 |
| 00 A4 00 00 02 10 01/02 | SELECT 1001 / 1002 |
| 00 20 00 00 03 12 34 56 | 苏州 PIN 校验 |
| 80 5C 00 02 04 | BALANCE CHECK（钱包余额） |
| 80 5C 05 02 10 | BALANCE CHECK（带 16B 记录） |
| 80 5C 00 01 04 | BALANCE CHECK（苏州变体） |

### 二、各卡型读取序列

#### 1. 通用 TU / 交通联合
```
SFI 0x04 → 卡号、有效期、魔数校验
SFI 0x05 → 备用信息、余额兜底
SELECT 1001 + BALANCE CHECK → 余额
SFI 0x15 → 卡信息
SFI 0x17 → 城市信息
SFI 0x19 rec6 → 杭州/宁波 POS
SFI 0x18 rec1..31 → 交易记录（23B）
SFI 0x06 rec1..31 → CU/TFT 附加交易

城市特化：
- 北京(1000)：SELECT BMAC → SFI 0x03 rec1
- 上海(2900)：SFI 0x11, SFI 0x14, SFI 0x17, SFI 0x10
- 南京(3010)：SFI 0x07 rec1/2
- 常州(3020)：SFI 0x07 rec1
- 苏州(3050)：SFI 0x19 rec1
- 广州(5810)/佛山(5880)：SFI 0x19 rec1 → 折扣统计
- 全部城市：SFI 0x1E rec1..31 → 交联 TU 交易记录（48B）
```

#### 2. 岭南通 YCT
```
SFI 0x15 → 卡号、发卡机构码、有效期
SELECT PAY.TICL + BALANCE CHECK → 余额
SFI 0x08 rec1 → 折扣统计（月乘车次数、累计金额）
SFI 0x15 → 充值月份
SFI 0x08 rec17 → 最近地铁上车站
SFI 0x18 rec1..31 → 交易记录
```

#### 3. 深圳 SZT
```
SFI 0x15 → 卡信息
SFI 0x19 rec1 → 深圳统计
SFI 0x18 + SFI 0x10 → 交易记录
```

#### 4. 苏州 SUZ
```
SELECT MF → SFI 0x06
SELECT SZTK_ZYY + BALANCE CHECK(f844i) → 余额
SFI 0x15 → 卡信息
SFI 0x19 rec1 → 苏州折扣统计
SFI 0x18 → 交易记录
```

#### 5. 天津 TFT
```
SFI 0x15 → 卡信息
SFI 0x1A rec2 → 天津统计
SFI 0x18, SFI 0x10, SFI 0x09 → 交易记录
SELECT 1002 → SFI 0x1A → 12 个月逐月余额
```

#### 6. 住建部 CU
```
SFI 0x18
FI 0x10
SFI 0x06
上海: SFI 0x1A
```

### 三、数据结构

**优惠统计记录（SFI 0x19 rec 1 / SFI 0x08）**：
```
偏移   字段           说明
[3-4]  年月           26 08 → 2026 年 08 月
[6]    本月乘车次数   0A → 10 次
[7]    总乘车次数     0A → 10 次
[10-13] 累计金额      13 88 → 5000 分 = ¥50.00
```

</details>

---

## 贡献指南

欢迎提交 Pull Request！以下几点请留意：

- **架构哲学** — 所有功能基于卡原始记录和站名数据库的记录，如无必要不增加额外匹配规则，做到通配，额外规则需打SP Rule标签，不保存可能解析错误的数据（需要的话放缓存文件夹）
- **测试通过** — 确保功能正常、无明显 Bug，PR 下可以通过输入`/build-apk`触发 CI 构建 APK 测试
- **标注影响范围** — 说明改动涉及哪些卡片类型 / 城市 / 模块，方便 review

> ⚠️ **关于 AI 生成代码**：本项目 99% 由 AI 辅助开发，AI 作者不一定完全理解每一行代码的深层含义。如果你发现不合理的实现，欢迎指出并修正。

## 许可证

本项目使用 [MIT 许可证](LICENSE)。
