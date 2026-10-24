<div align="center">
<p><b>简体中文</b> | <a href="README.en.md">English</a></p>
  <h1>行旅录 · TransitU</h1>
  <p><strong>读交通卡,记每一程</strong></p>
  <p>把卡里的每一次经过,整理成可回看的出行记录。</p>
  <p><a href="https://github.com/HaoTian22/TU-Reader/releases"><b>⬇️ 下载最新版 APK</b></a> · <a href="TECHNICAL.md">技术文档</a> · <a href="https://github.com/HaoTian22/TU-Reader/issues">反馈问题</a></p>
</div>

<p align="center">
  <a href="https://github.com/HaoTian22/TU-Reader/actions/workflows/build-prerelease.yml"><img src="https://github.com/HaoTian22/TU-Reader/actions/workflows/build-prerelease.yml/badge.svg" alt="Build status" /></a>
  <a href="https://github.com/HaoTian22/TU-Reader/releases"><img src="https://img.shields.io/github/v/release/HaoTian22/TU-Reader?include_prereleases&label=release" alt="Latest release" /></a>
  <a href="https://github.com/HaoTian22/TU-Reader/blob/main/LICENSE"><img src="https://img.shields.io/github/license/HaoTian22/TU-Reader" alt="License" /></a>
  <a href="https://github.com/HaoTian22/TU-Reader/stargazers"><img src="https://img.shields.io/github/stars/HaoTian22/TU-Reader?style=flat" alt="GitHub stars" /></a>
</p>

行旅录(TransitU)是一款专注于交通卡读取与出行记录的 Android 应用。将手机靠近交通卡,即可读取余额、交易记录和城市线路信息,并在本地生成统计图表与行程地图。

基于公开交通卡协议文档([NFC Wiki 智能卡手册](https://nfcwiki.com))实现,只读卡,不写卡、不修改余额。

## 目录

[功能](#功能) · [支持的卡片](#支持的卡片) · [截图](#截图) · [快速开始](#快速开始) · [隐私与数据](#隐私与数据) · [常见问题](#常见问题) · [反馈](#反馈与交流) · [贡献](#贡献指南) · [声明](#声明与许可证)

## 功能

| 模块 | 内容                                                                         |
| ---- | ---------------------------------------------------------------------------- |
| 读卡 | 卡号、发卡日期、有效期、发卡机构、实时余额、发卡城市                         |
| 记账 | 最近交易(时间、金额、站点、卡类型)、本月乘车次数、累计优惠                   |
| 分析 | 按周/月/年/自定义范围的消费趋势;总消费、乘车次数、日均消费;高频车站与线路    |
| 回看 | 地图轨迹、路线播放(播放/暂停/变速)、腾讯地图换乘方案(可只看起终点或展开全程) |
| 数据 | 导出 CSV / JSON / SQLite / 读取日志;在线更新站名与线路,无需重装              |

## 支持的卡片

| 卡片                      | 说明                          | 状态                                          |
| ------------------------- | ----------------------------- | --------------------------------------------- |
| 交通联合卡 (T-Union)      | 全国互联互通卡,覆盖 300+ 城市 | ✅ 支持                                        |
| 深圳通 (SZT)              | 深圳市公共交通卡              | ✅ 支持                                        |
| 岭南通 / 羊城通 (LNT/YCT) | 广东互联互通卡                | ⚠️ 部分:仅支持部分广州/佛山地铁,公交会识别错误 |
| 八达通 (Octopus)          | 香港八达通卡                  | ⚠️ 仅余额                                      |
| 澳门通 (Macau Pass)       | 澳门公共交通卡                | ✅ 支持（但无法正确识别公交/轻轨及线路）        |
| 苏州通 (SUZ)              | 苏州市交通卡                  | ❓ 未验证                                      |
| 天津城市卡 (TFT)          | 天津市公共交通卡              | ❓ 未验证                                      |
| 住建部 CU (City Union)    | 城市一卡通系统                | ❓ 未验证                                      |

你的卡不在列表中,或读取结果异常?欢迎提交 Issue 并附上读取日志。

## 截图

<table>
  <tr>
    <td align="center" width="50%"><img height="560" alt="主界面 - 卡片列表" src="https://github.com/user-attachments/assets/8733cd2e-9692-4a15-9d1d-b7fa6b6f9d95" /><br />卡片列表</td>
    <td align="center" width="50%"><img height="560" alt="交易记录 - 交易列表" src="https://github.com/user-attachments/assets/6ec9b6e9-dd35-4887-a2b5-5ec74da8b76c" /><br />交易列表</td>
  </tr>
  <tr>
    <td align="center" width="50%"><img height="560" alt="主界面 - 卡片页面" src="https://github.com/user-attachments/assets/b1755775-52b4-41df-be10-0d487a2c5300" /><br />卡片页面</td>
    <td align="center" width="50%"><img height="560" alt="卡片信息" src="https://github.com/user-attachments/assets/75a21f14-a5e4-4edf-b526-36bcb2eebc4a" /><br />卡片信息</td>
  </tr>
  <tr>
    <td align="center" width="50%"><img height="560" alt="统计分析 - 消费与乘车图表" src="https://github.com/user-attachments/assets/8a355300-a594-4901-a182-ff1970867047" /><br />统计分析</td>
    <td align="center" width="50%"><img height="560" alt="交易记录 - 交易详情" src="https://github.com/user-attachments/assets/6c89401c-7494-4014-b739-fbd0abad3ab8" /><br />交易详情</td>
  </tr>
</table>

## 快速开始

**环境要求**:Android 10+(API 29+)、支持 NFC 的设备,且已开启 NFC。

1. 从 [Releases](https://github.com/HaoTian22/TU-Reader/releases) 下载最新 APK 并安装。
2. 打开应用,授权 NFC 权限。
3. 将交通卡贴近手机背面 NFC 区域,保持不动直到读取完成。
4. 自动识别卡类型,查看余额、交易与统计。

## 隐私与数据

- **本地优先**:卡片与交易记录保存在设备本地,可单独清除或导出迁移。
- **联网行为**:仅在以下情况联网 —— 更新站名/线路数据;使用腾讯地图站点搜索与路线规划;开启「公开上传纠错」后上传你提交的站名/线路纠错。
- **上传内容**:表单上的所有内容，可选包含原始字段
- **只读**:应用不会写卡,也不会修改卡内余额。

## 常见问题

**读不到卡怎么办?** 调整卡与手机 NFC 天线的相对位置(多数机型在背部上半部),取下金属壳或带金属片的保护套,并确认 NFC 已开启。

**手机不支持 NFC?** 本应用依赖 NFC 读卡,无法使用。

**读取结果和官方不一致?** 见下方「声明」,并欢迎附读取日志提交 Issue。

**需要联网吗?** 读卡和本地统计不需要;站名更新与路线规划需要。

**iOS计划？** 没有，暂时无苹果设备无法调试，有兴趣可以自行参照开发

## 反馈与交流

- **问题反馈 / 功能建议**:[GitHub Issues](https://github.com/HaoTian22/TU-Reader/issues),也可从应用「设置 → 反馈入口」进入。
- **使用交流 / 想法讨论**:[GitHub Discussions](https://github.com/HaoTian22/TU-Reader/discussions)。
- **站名 / 线路纠错**:在交易详情页点击「反馈」,填写正确的城市、线路和站名;保存后立即在本机生效,开启「公开上传纠错」可帮助改进站名数据。

提交问题时请提供:应用版本、手机型号、Android 版本、卡片类型与城市、复现步骤,以及相关截图或读取日志。

## 贡献指南

欢迎提交 Pull Request,请注意:

- **架构原则**:所有功能基于卡原始记录和站名数据库,如无必要不增加额外匹配规则,力求通配;确需额外规则时打 `SP Rule` 标签(<!-- TODO: 补一句 SP Rule 的定义,或链接到 TECHNICAL.md 对应章节 -->);不保存可能解析错误的数据(需要的话放缓存目录)。
- **测试通过**:确保功能正常、无明显 Bug。PR 下评论 `/build-apk` 可触发 CI 构建测试 APK。
- **标注影响范围**:说明改动涉及的卡片类型 / 城市 / 模块,方便 review。
- 实现原理、构建配置、读卡流程、数据库架构、在线更新机制与 APDU 参考见 [TECHNICAL.md](TECHNICAL.md)。

## 声明与许可证

- 本项目为非官方工具,与任何交通卡发行方或运营机构无关。读取结果基于公开协议文档解析,可能与官方记录存在差异,不应作为扣费争议的唯一依据。
- 本项目约 99% 由 AI 辅助开发(Vibe-Coding)。AI 不一定完全理解每一行代码的深层含义,如果你发现不合理的实现,欢迎指出并修正。
- 本项目使用 [MIT 许可证](LICENSE)。

## 数据来源与致谢

### 数据来源

应用使用的站名、线路和城市映射数据来自 [HaoTian22/tripreader-data](https://github.com/HaoTian22/tripreader-data)(“读卡识途”项目公开数据),应用内「站名更新」即从该数据拉取更新。

### 致谢

- **[domosekai/tripreader-data](https://github.com/domosekai/tripreader-data)**:上述数据的最初版本,由“读卡识途”项目原作者及其他贡献者采集并公开(该仓库已于 2025 年 3 月归档)。
- **“读卡识途”项目**:本项目在交通卡数据解析和站名数据整理方面受其启发,感谢原作者与所有贡献者。
- **NFC Wiki 智能卡手册**:提供公开的交通卡协议资料。

原始数据采用 [CC BY 4.0(知识共享署名 4.0 国际)](https://creativecommons.org/licenses/by/4.0/deed.zh-hans) 许可,使用和再分发时需保留署名。数据由贡献者采集,不保证准确性。
