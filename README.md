<div align="center">
  <h1>行旅录 · TransitU</h1>
  <p><strong>读交通卡，记每一程</strong></p>
  <p>把卡里的每一次经过，整理成可回看的出行记录。</p>
</div>

<div align="center">
  <table>
    <tr>
      <td align="center" width="25%"><strong>读卡</strong><br />余额 · 卡号 · 有效期</td>
      <td align="center" width="25%"><strong>记账</strong><br />交易 · 充值 · 导出</td>
      <td align="center" width="25%"><strong>分析</strong><br />趋势 · 频次 · 消费</td>
      <td align="center" width="25%"><strong>回看</strong><br />地图 · 线路 · 行程</td>
    </tr>
  </table>
</div>

<p align="center">
  <a href="https://github.com/HaoTian22/TU-Reader/actions/workflows/build-prerelease.yml"><img src="https://github.com/HaoTian22/TU-Reader/actions/workflows/build-prerelease.yml/badge.svg" alt="Build status" /></a>
  <a href="https://github.com/HaoTian22/TU-Reader/releases"><img src="https://img.shields.io/github/v/release/HaoTian22/TU-Reader?include_prereleases&label=release" alt="Latest release" /></a>
  <a href="https://github.com/HaoTian22/TU-Reader/blob/main/LICENSE"><img src="https://img.shields.io/github/license/HaoTian22/TU-Reader" alt="License" /></a>
  <a href="https://github.com/HaoTian22/TU-Reader/stargazers"><img src="https://img.shields.io/github/stars/HaoTian22/TU-Reader?style=flat" alt="GitHub stars" /></a>
</p>

行旅录（TransitU）是一款专注于交通卡读取与出行记录的 Android 应用。将手机靠近交通卡，即可读取余额、交易记录和城市线路信息，把每一次经过都留存下来。

基于公开交通卡协议文档（NFC Wiki 智能卡手册）搭建，支持多种主流交通卡的余额、交易记录和统计数据读取。

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

### 分析图表
- **消费趋势** — 按周、月、年或自定义时间范围查看消费变化
- **核心指标** — 总消费、乘车次数、日均消费，快速了解出行开销
- **高频出行** — 统计最常去的车站和最常乘坐的线路
- **卡片优惠** — 查看月度乘车次数与累计优惠金额

### 行程地图与路线
- **地图轨迹** — 根据交易中的站点信息回看行程经过的城市、线路和车站
- **路线播放** — 支持播放/暂停、播放速度调整，按时间顺序回放行程
- **路线规划** — 结合腾讯地图站点搜索与公交路线规划，查看站点间的换乘方案
- **换乘展示** — 可选择仅显示起终点，或展开完整换乘过程

### 数据管理
- **数据导出** — 支持 CSV、JSON、SQLite 数据库及读取日志导出
- **站名更新** — 在线更新站名、线路和城市映射，无需重新安装应用
- **本地优先** — 卡片与交易记录保存在设备本地，可单独清除或迁移

## 截图演示

<table>
  <tr>
    <td align="center" width="50%">
      <img height="560" alt="主界面 - 卡片列表" src="https://github.com/user-attachments/assets/860dece1-ab59-4caf-afa1-dfd8e8c1286e" />
    </td>
    <td align="center" width="50%">
      <img height="560" alt="交易记录 - 交易列表" src="https://github.com/user-attachments/assets/24c639c7-0086-455b-a588-ea10b1cb6009" />
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <img height="560" alt="统计分析 - 消费与乘车图表" src="https://github.com/user-attachments/assets/e6e1468e-2b22-40c7-b624-d9f726cd9bf8" />
    </td>
    <td align="center" width="50%">
      <img height="560" alt="交易记录 - 交易详情" src="https://github.com/user-attachments/assets/a4634852-ee96-46b3-81d7-013e2aee6c5b" />
    </td>
  </tr>
</table>

## 快速开始

### 环境要求
- Android 10+ (API 29+)
- 支持 NFC 的 Android 设备
- NFC 功能已开启

### 安装使用

1. **下载 APK** — 从 [Releases](https://github.com/HaoTian22/TU-Reader/releases) 下载最新版本
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
- 👍 **统一智能匹配** — 通过线路/站点编码和终端号解析站点信息，连表取优：长度 → 2字符对齐 → 非0字符长度 → 起始位置
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
| A00000000386980701 | 住建部 CPU 交通卡 / 数字城市一卡通（CU） |
| A000000632010105 | 交通联合 |
| PAY.SZT（AID使用ASCII解析） | 深圳通 |
| SUXIN.DDF01（AID使用ASCII解析） | 苏州（需 PIN） |
| SZTK_ZYY（AID使用ASCII解析） | 苏州 CIKA |
| PAY.APPY（AID使用ASCII解析） | 岭南通 YCT1 |
| PAY.TICL（AID使用ASCII解析） | 岭南通 YCT2 |
| 91560000 144D4F542E424D4143303031 | 北京 |
| D1560000 15B9ABB9B2D3A6D3C3 | 天津 |
| 00 A4 00 00 02 3F 00/01 | SELECT MF / 3F01 |
| 00 A4 00 00 02 10 01/02 | SELECT 1001 / 1002 |
| 00 20 00 00 03 12 34 56 | 苏州 PIN 校验 |
| 80 5C 00 02 04 | BALANCE CHECK（钱包余额） |
| 80 5C 05 02 10 | BALANCE CHECK（带 16B 记录） |
| 80 5C 00 01 04 | BALANCE CHECK（苏州变体） |

### 二、各卡型读取序列

#### 1. 通用 TU / 交通联合卡

| 应用 / SFI | 如何读取 | 含义 |
|---|---|---|
| TU AID `A000000632010105` / `A000000632010106` | `SELECT` 应用 | 选择交通联合钱包 |
| SFI `0x05` | `READ BINARY`：`00 B0 95 00 00` | 备用信息（发卡日期） |
| SFI `0x06` | `READ BINARY`：`00 B0 95 00 00` | 备用信息（发卡日期） |
| SFI `0x15` | `READ BINARY`：`00 B0 95 00 00` | 卡号、发卡日期、有效期 |
| SFI `0x1E` rec1..30 | `READ RECORD`：P2=`F4`，例如 `00 B2 01 F4 00` | 终端、城市、线路、站点、旅程及记录余额 |
| SFI `0x18` rec1..30 | `READ RECORD`：P2=`C4`，Le=`17`，例如 `00 B2 01 C4 17` | 主交易记录，每条 23 字节 |
| SFI `0x19` rec1（广州/佛山） | `READ RECORD`：P2=`CC`，例如 `00 B2 01 CC 00` | 折扣统计 |
| SFI `0x19` 各 rec（杭州） | `READ RECORD`：P2=`CC` | 杭州乘车优惠月累乘统计（布局见「杭州月累乘统计」） |
| `80 5C 00 02 04` | `BALANCE CHECK` | 钱包余额；失败时用最新 SFI `0x1E` 记录中的余额兜底 |
| 其他 SFI `0x01..0x1F` | 先 `READ BINARY`，失败后逐条 `READ RECORD` | 当前只保存原始数据，不做专用字段解析 |

**城市自定义：**

- 北京（1000）：`SELECT BMAC` → SFI `0x03` rec1；字段 offset / 格式：未知
- 上海（2900）：SFI `0x11`、`0x14`、`0x17`、`0x10`；字段 offset / 格式：未知
- 南京（3010）：SFI `0x07` rec1/2；字段 offset / 格式：未知
- 常州（3020）：SFI `0x07` rec1；字段 offset / 格式：未知
- 苏州（3050）：SFI `0x19` rec1；字段 offset / 格式：未知
- 广州（5810）/佛山（5880）：SFI `0x19` rec1 → 折扣统计；字段布局见下方 SFI `0x19` 表格
- 杭州（3301）：SFI `0x19` 变长记录 → 乘车优惠月累乘统计；字段布局见下方「杭州月累乘统计」（CU 钱包同格式记录在 SFI `0x17`）
- 全部城市：SFI `0x1E` rec1..31 → 交联 TU 交易记录（48B）；字段 offset / 格式：见下方 SFI `0x1E` 表格，未列字段为未知

**SFI `0x05` 卡信息：**

**SFI `0x06` 卡信息：**

**SFI `0x15` 卡信息：**

| Offset | 长度 | 数据格式 | 说明 |
|---:|---:|---|---|
| `[0..8)` | 8B | HEX | 发卡机构标识 |
| `[8]` | 1B | HEX | 应用类型 |
| `[9]` | 1B | HEX | 应用版本 |
| `[10..20)` | 10B | BCD | 卡号 |
| `[20..24)` | 4B | BCD `YYYYMMDD` | 发卡 / 生效日期 |
| `[24..28)` | 4B | BCD `YYYYMMDD` | 有效期 |
| `[28..30)` | 2B | HEX | 发卡机构自定义 FCI 数据 |

文件属性：二进制文件，文件大小 `0x1E`（30B）；读权限为自由读取，写权限为 SM（安全报文）。

**SFI `0x1E` 旅程 / 终端记录：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[0..1)` | 1B | BCD | 类型 |
| `[1..9)` | 8B | BCD | 终端号 |
| `[9]` | 1B | 无符号整数 | 交通类型 subtype |
| `[10..17)` | 6B | BCD | 线路码/站点码 |
| `[19..21)` | 2B | 大端整数 | 交易金额，单位为分 |
| `[21..25)` | 4B | 大端整数 | 交易后余额，单位为分 |
| `[25..32)` | 7B | BCD `YYYYMMDDhhmmss` | 交易时间 |
| `[32..34)` | 2B | BCD | 城市码 |
| `[34..41)` | 7B | BCD | 机构 |]

**SFI `0x18` 主交易记录：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[0..2)` | 2B | 大端整数 | 记录序号 |
| `[6..9)` | 3B | 大端整数 | 交易金额，单位为分 |
| `[9]` | 1B | 十六进制 | 交易类型 |
| `[10..16)` | 6B | BCD | 终端号或 POS 信息 |
| `[16..20)` | 4B | BCD `YYYYMMDD` | 交易日期 |
| `[20..23)` | 3B | BCD `hhmmss` | 交易时间 |

**SFI `0x19` rec1 折扣统计（广州/佛山）：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[3]` | 1B | BCD | 统计年份，`26` → 2026 年 |
| `[4]` | 1B | BCD | 统计月份，`08` → 8 月 |
| `[6]` | 1B | 无符号整数 | 本月地铁乘车次数 |
| `[7]` | 1B | 无符号整数 | 本月总乘车次数 |
| `[10..12)` | 2B | 大端整数 | 地铁累计金额，单位为分 |
| `[12..14)` | 2B | 大端整数 | 总累计金额，单位为分 |

**杭州月累乘统计（CU SFI `0x17` / TU SFI `0x19` 变长记录）：**

杭州乘车优惠（自然月内累计消费 50 元以内每乘次 9 折；50（含）至 100 元 7 折；100 元（含）以上 5 折）
的当月累乘金额由卡内变长记录维护：住建部 CU 双标卡写在 CU 钱包 SFI `0x17`，TU 卡写在 SFI `0x19`，
两种协议共用同一记录格式。单条记录外层为 `[tag][len][payload]` 包裹，payload 内含若干
以 `[FA][序号]` 开头的条目；其中月累乘金额条目：

| Offset（相对 `FA` 锚点） | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[0]` | 1B | HEX | 条目标记 `0xFA` |
| `[1]` | 1B | HEX | 条目序号（样本中该条目为 `02`） |
| `[2..6)` | 4B | BCD `YYYYMMDD` | 累乘金额最后刷新日期；之后没有新乘次则一直保持旧值（不跨月清零） |
| `[6..9)` | 3B | HEX | 未明 |
| `[9..11)` | 2B | 大端整数 | 当月累计消费金额，单位为分 |

实卡样例（48 字节记录）：`06 2E … FA 02 | 20 24 06 15 | 00 18 60 | 03 CA …`
→ 外层 tag `06` len `2E`；刷新日期 2024-06-15、当月累计 `0x03CA` = 970 分 = ¥9.70。

展示条件与广佛折扣统计一致：卡片交易城市出现「杭州」且刷新日期在本月才采用，旧月份的残留值视为过期隐藏。
城市→读卡位置/字段区域/折扣档位 的映射统一配置在 `model/CityDiscount.kt` 的 `DiscountRegistry`，
通用解析见 `RecordDecoder.parseMonthAccumulation`。

#### 2. 岭南通 / 羊城通 YCT

| 应用 / SFI | 如何读取 | 含义 |
|---|---|---|
| `PAY.APPY` | `SELECT` 应用 | 基本信息应用 |
| `PAY.APPY` / SFI `0x15` | `READ BINARY`，优先尝试 `00 B0 95 00 46` | 卡号、发卡机构码、有效期 |
| `PAY.APPY` / SFI `0x08` rec1 | `READ RECORD`：`00 B2 01 44 16` | 折扣统计 |
| `PAY.TICL` | `SELECT` 应用后执行 `80 5C 00 02 04` | 钱包余额及交易上下文 |
| SFI `0x18` rec1..30 | `READ RECORD`：P2=`C4`，Le=`17` | 岭南通交易记录 |
| SFI `0x1E` rec1..30 | `READ RECORD` | 双协议卡中的 TU 旅程记录 |
| 其他 SFI `0x01..0x1F` | 先 `READ BINARY`，失败后逐条 `READ RECORD` | 原始数据归档 |

**SFI `0x15` 基本信息：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[11..16)` | 5B | BCD 十进制 | 岭南通卡号 |
| `[23..27)` | 4B | BCD `YYYYMMDD` | 发卡日期 |
| `[27..31)` | 4B | BCD `YYYYMMDD` | 有效期 |
| `[48..52)` | 4B | HEX | 发卡机构码；全 0 时视为未知 |

**SFI `0x08` rec1 折扣统计：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[3]` | 1B | BCD | 统计年份，计算为 `2000 + YY` |
| `[4]` | 1B | BCD | 统计月份，必须为 `01..12` |
| `[6]` | 1B | 无符号整数 | 本月地铁乘车次数 |
| `[7]` | 1B | 无符号整数 | 本月总乘车次数 |
| `[10..12)` | 2B | 大端整数 | 地铁累计金额，单位为分；`0x1388` → ¥50.00 |
| `[12..14)` | 2B | 大端整数 | 总累计金额，单位为分 |

**SFI `0x18` 岭南通交易记录：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[0..2)` | 2B | 大端整数 | 记录序号 |
| `[6..9)` | 3B | 大端整数 | 交易金额，单位为分 |
| `[9]` | 1B | 十六进制 | 交易类型 |
| `[10..16)` | 6B | BCD / 原始 HEX | 终端号或 POS 信息 |
| `[18..20)` | 2B | BCD `MMDD` | 交易日期，年份由统计月份和记录连续性推断 |
| `[20..22)` | 2B | BCD `hhmm` | 交易时间，代码补 `00` 秒 |
| `[22]` | 1B | 十六进制 | 交易 subtype |

#### 3. 深圳通 SZT

| 应用 / SFI | 如何读取 | 含义 |
|---|---|---|
| `PAY.SZT` | `SELECT` 应用 | 深圳通钱包 |
| SFI `0x15` | `READ BINARY`：`00 B0 95 00 00` | 深圳通卡号、发卡日期、有效期 |
| SFI `0x18` rec1..30 | `READ RECORD`：P2=`C4`，Le=`17` | 深圳通交易记录 |
| SFI `0x19` rec1（如存在） | 通用探测后解析 | 深圳统计记录，字段布局同 TU `0x19` |
| 其他 SFI `0x01..0x1F` | 先 `READ BINARY`，失败后逐条 `READ RECORD` | 原始数据归档 |
| TU AID（双协议卡） | 重新 `SELECT` TU AID 后按 TU 流程读取 | 第二钱包、TU 卡号及 TU 交易 |

**SFI `0x15` 卡信息：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[16..20)` | 4B | 原始 HEX，先按字节反向，再按大端整数转十进制 | 深圳通卡号；例如 `C2 39 85 34` → `34 85 39 C2` → `881146306` |
| `[20..24)` | 4B | BCD `YYYYMMDD` | 发卡日期 |
| `[24..28)` | 4B | BCD `YYYYMMDD` | 有效期 |

**SFI `0x18` 深圳通交易记录：** 使用 TU 主交易记录的 `[0..2)`、`[6..9)`、`[9]`、`[10..16)`、`[16..20)`、`[20..23)` 字段布局。

#### 4. 苏州通 SUXIN / SZTK

| 应用 / SFI | 如何读取 | 含义 |
|---|---|---|
| `SUXIN.DDF01` / `SZTK` 应用 | `SELECT` 应用 | 选择苏州钱包应用 |
| SFI `0x15` | `READ BINARY`：`00 B0 95 00 00` | 卡号、发卡日期、有效期 |
| SFI `0x18` rec1..30 | `READ RECORD`：P2=`C4`，Le=`17` | 交易记录 |
| 其他 SFI `0x01..0x1F` | 先 `READ BINARY`，失败后逐条 `READ RECORD` | 原始数据归档 |

**SFI `0x15` 卡信息：**

| Offset | 长度 | 数据格式 | 含义 |
|---|---:|---|---|
| `[10..20)` | 10B | BCD 十进制 | 应用序列号（卡号） |
| `[20..24)` | 4B | BCD `YYYYMMDD` | 发卡日期 |
| `[24..28)` | 4B | BCD `YYYYMMDD` | 有效期 |

**SFI `0x18` 交易记录：** 使用通用交易字段布局：序号 `[0..2)`、金额 `[6..9)`、类型 `[9]`、终端/POS `[10..16)`、日期 `[16..20)`、时间 `[20..23)`。

#### 5. 天津城市卡 TFT

| 应用 / SFI | 如何读取 | 含义 |
|---|---|---|
| TFT AID `D156000015B9ABB9B2D3A6D3C3` | `SELECT` 应用 | 天津钱包 |
| SFI `0x15` | `READ BINARY`：`00 B0 95 00 00` | 卡号、发卡日期、有效期 |
| SFI `0x18` rec1..30 | `READ RECORD`：P2=`C4`，Le=`17` | 主交易记录 |
| SFI `0x10` rec1..30 | `READ RECORD`：P2=`84`，Le=`17` | 附加交易记录 |
| SFI `0x09` rec1..30 | `READ RECORD`：P2=`4C`，Le=`17` | 附加交易记录 |
| 其他 SFI `0x01..0x1F` | 先 `READ BINARY`，失败后逐条 `READ RECORD` | 原始数据归档 |

**SFI `0x15` 卡信息：** 使用通用 `[10..20)` 10 字节 BCD 卡号、`[20..24)` 发卡日期、`[24..28)` 有效期布局。

**SFI `0x18`、`0x10`、`0x09` 交易记录：** 使用通用交易字段布局：序号 `[0..2)`、金额 `[6..9)`、类型 `[9]`、终端/POS `[10..16)`、日期 `[16..20)`、时间 `[20..23)`。

#### 6. 住建部 CU / City Union

| 应用 / SFI | 如何读取 | 含义 |
|---|---|---|
| `A00000000386980701` | `SELECT` 应用 | CU 钱包 |
| SFI `0x15` | `READ BINARY`：`00 B0 95 00 00` | 卡号、发卡日期、有效期 |
| SFI `0x17` | `READ RECORD`，变长记录文件 | 复合消费数据及锁定状态；杭州双标卡的月累乘统计也在此文件（见 TU 章节表格） |
| SFI `0x18` rec1..30 | `READ RECORD`：P2=`C4`，Le=`17` | CU 主交易记录 |
| SFI `0x10` rec1..30 | `READ RECORD`：P2=`84`，Le=`17` | CU 附加交易记录 |
| SFI `0x06` rec1..30 | `READ RECORD`：P2=`34`，Le=`17` | CU 附加交易记录 |
| SFI `0x1A` rec1..30 | `READ RECORD`：P2=`D4`，Le=`17` | CU 附加交易记录 |
| TU AID（双标准卡） | 重新 `SELECT` TU AID 后按 TU 流程读取 | 第二钱包及 TU 交易 |

**SFI `0x15` 卡信息（CU）：**

| Offset | 长度 | 数据元 | 数据格式 | 含义 |
|---|---:|---|---|---|
| `[0..2)` | 2B | 发卡方代码 | BCD | 发卡方代码 |
| `[2..4)` | 2B | 城市 / 项目代码 | HEX | 城市或项目代码 |
| `[4]` | 1B | 多算法支持 | BCD | 多算法支持标识 |
| `[5]` | 1B | 行业代码 | BCD | 行业代码 |
| `[6..8)` | 2B | 预留 | HEX | 预留 |
| `[8]` | 1B | 互联互通启用标识 | HEX | `0x00` 未启用，非 `0x00` 启用 |
| `[9]` | 1B | 应用版本 | HEX | 应用版本 |
| `[10..12)` | 2B | 互联互通标识 | HEX | 与城市 / 项目代码相同 |
| `[12..20)` | 8B | 用户卡应用序列号 | HEX 大端整数 → 十进制 | CU 卡号 |
| `[20..24)` | 4B | 应用生效日期 | BCD `YYYYMMDD` | 发卡 / 生效日期 |
| `[24..28)` | 4B | 应用失效日期 | BCD `YYYYMMDD` | 有效期 |
| `[28..30)` | 2B | 预留 | HEX | 预留 |

文件属性：二进制文件，文件大小 `0x1E`（30B）；读权限为自由读取，写权限为 SM（安全报文）。

**SFI `0x17` 复合消费数据文件：**

| 文件属性 | 内容 |
|---|---|
| 文件类型 | 变长记录文件 |
| 文件大小 | `0xA0` |
| 权限 | 读 = 自由；写 = SM / 复合交易 |
| 记录标识 | `0x09` |

| Offset | 长度 | 数据元 | 数据格式 | 含义 |
|---|---:|---|---|---|
| `[0]` | 1B | 复合消费记录标识 | HEX，通常为 `0x09` | 记录类型 |
| `[1]` | 1B | 复合消费数据长度 | HEX | 后续复合消费数据长度 |
| `[2]` | 1B | 复合消费锁定标识 | HEX | `0x00` 允许，非 `0x00` 禁止 |
| `[3..48)` | 45B | 应用方自定义 | 自定义 | 当前 offset / 格式未知 |

**SFI `0x18` 电子钱包本地消费交易明细：**

| Offset | 长度 | 数据元 | 数据格式 | 含义 |
|---|---:|---|---|---|
| `[0..2)` | 2B | 电子钱包消费交易序号 | HEX | 交易序号 |
| `[2..5)` | 3B | 预留 | HEX | 未使用 |
| `[5..9)` | 4B | 交易金额 | HEX，大端整数 | 单位为分 |
| `[9]` | 1B | 交易类型 | HEX | `0x06` 消费，`0x09` 复合消费 |
| `[10..16)` | 6B | 交易终端编号 | BCD | 终端号 |
| `[16..20)` | 4B | 交易日期 | BCD `YYYYMMDD` | 交易日期 |
| `[20..23)` | 3B | 交易时间 | BCD `HHMMSS` | 交易时间 |

**SFI `0x10` 电子钱包异地消费交易明细：**

| Offset | 长度 | 数据元 | 数据格式 | 含义 |
|---|---:|---|---|---|
| `[0..2)` | 2B | 电子钱包消费交易序号 | HEX | 交易序号 |
| `[2..5)` | 3B | 预留 | HEX | 未使用 |
| `[5..9)` | 4B | 交易金额 | HEX，大端整数 | 单位为分 |
| `[9]` | 1B | 交易类型 | HEX | `0x06` 消费，`0x09` 复合消费 |
| `[10..16)` | 6B | 交易终端编号 | BCD | 终端号 |
| `[16..20)` | 4B | 交易日期 | BCD `YYYYMMDD` | 交易日期 |
| `[20..23)` | 3B | 交易时间 | BCD `HHMMSS` | 交易时间 |

**SFI `0x1A` 电子钱包充值消费交易明细：**

| Offset | 长度 | 数据元 | 数据格式 | 含义 |
|---|---:|---|---|---|
| `[0..2)` | 2B | 电子钱包充值交易序号 | HEX | 交易序号 |
| `[2..5)` | 3B | 预留 | HEX | 未使用 |
| `[5..9)` | 4B | 交易金额 | HEX，大端整数 | 单位为分 |
| `[9]` | 1B | 交易类型 | HEX | `0x02` |
| `[10..16)` | 6B | 交易终端编号 | BCD | 终端号 |
| `[16..20)` | 4B | 交易日期 | BCD `YYYYMMDD` | 交易日期 |
| `[20..23)` | 3B | 交易时间 | BCD `HHMMSS` | 交易时间 |

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
