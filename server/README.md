# 公开纠错服务

服务使用 Python 标准库，启动命令为 `python app.py`；Docker 部署继续使用
`docker compose up -d --build`。端点为 `GET /health`、`GET /v1/feedback-policy` 和 `POST /v1/overrides`。

## 反馈策略（服务端控制）

App 打开交易详情页时拉取 `GET /v1/feedback-policy`，据此决定反馈表单能否公开上传：

```json
{ "publish": true, "imported": false, "terminalMismatch": false, "outdated": false }
```

| 键 | 含义 | 默认 |
| --- | --- | --- |
| `publish` | 公开上传总开关；`false` 时 App 全部改为引导 GitHub，服务端 `POST /v1/overrides` 同时返回 403 | `true` |
| `imported` | 是否允许导入卡片的记录公开上传 | `false` |
| `terminalMismatch` | 是否允许终端号与钱包类型不符的记录公开上传 | `false` |
| `outdated` | 是否允许本机站名映射表 / 公交线路规则落后于已发布版本时公开上传（`false` 时上传开关处提示并可一键更新） | `false` |

策略存于数据目录 `feedback_policy.json`，只写需要覆盖的键；每次请求重新读取，修改即时生效，
无需重启。文件缺失、损坏或值非布尔时按默认值处理。App 拉取失败时沿用上次缓存，从未拉到时用同一组默认值。

## 单文件存储

数据目录由 `FEEDBACK_DATA_DIR` 指定，默认是 `server/data/`。唯一持久化文件是
`overrides.json`，内容示例：

```json
{
  "60200010101|公交": {
    "csv": "6020,0010101,公交,1,",
    "standard": "TU",
    "locationCityCode": "6020",
    "locationCityName": "东莞",
    "locationSource": "MANUAL"
  }
}
```

- 键为完整设备编号（`prefix + code`）与交通类型的组合；同编号的公交、地铁、城际分别保存。
- `csv` 是一条标准 CSV 记录，列顺序为 `Prefix,Code,Type,Line,Station`，无表头和末尾换行。
  逗号、引号按 CSV 规则转义，空线路、空站名仍保留对应列。
- `standard` 保存卡片/协议类型；地区字段可以为 `null`。
- 同键反馈更新整条记录；更新在进程锁内完成，通过临时文件原子替换 JSON，避免 CSV 与元数据不同步。
  JSON 损坏或写入失败时返回 HTTP 500，保留原数据。

按全新服务器部署，无旧格式迁移。首次成功提交时创建 JSON，后续提交继续更新该文件。

## 地区含义

`locationCityCode` / `locationCityName` 表示设备或站点的地点归属城市，允许与设备编号前缀
或交易声明城市不同，供跨城线路等场景使用。纠错界面的地区选择保存用户指定的归属城市。

上传字段 `locationSource` 必填，只分两类：

| 值 | 含义 |
| --- | --- |
| `AUTO` | 本次纠错中保留自动填充的地区，未手动编辑或重新选择 |
| `MANUAL` | 本次纠错中用户输入或重新选择过地区 |

交易页纠错和本地映射编辑页都使用同一规则。仅展开城市候选列表、修改线路或站名不会改变
地区来源；手动编辑后即使选回原城市，仍记为 `MANUAL`。每次打开表单重新计算本次来源，
所以 `AUTO` 表示表单填充方式，不保证地区本身是精确定位。

客户端用于地图和行程判断的内部 `LocationSource` 保留原来的定位精度分类，上传时使用独立的
`FeedbackLocationSource`。服务端不接受内部定位枚举、缺失来源或空来源，返回 HTTP 422；
新客户端和服务端需配套更新。

## 可选原始记录

交易详情页反馈表单提供默认关闭的「提供更多信息」开关，开启「公开上传纠错」后才可用。
关闭公开上传时会同时关闭此选项；每次打开表单都重新默认关闭。提示用户原始数据可能包含
余额、交易时间、金额等信息。无原始记录的本地映射编辑表单不提供此选项。

用户主动开启后，客户端额外提交 `rawRecord` 字符串，包含该条交易的全部原始 hex
（主记录、合并记录、配对旅程记录），各段 SFI / 协议标识及 Match 匹配信息。
内容在打开表单时固定，避免保存纠错后的
重新解析改变匹配信息。未开启时不提交该字段。

服务端允许省略 `rawRecord` 或传 `null`；有值时必须是非空白字符串，最多 32 KiB UTF-8
字节，保留完整内容和换行，原样写入同一条 `overrides.json` 记录的 `rawRecord` 字段。
请求体上限为 256 KiB，兼容 JSON 转义。
同设备编号、同交通类型的再次提交替换整条记录，未提供原始数据时移除该字段；
其他交通类型的记录不受影响。该字段可选，旧客户端仍可提交。

## 提交时间

客户端每次提交附带 `submittedAt`：手机本地时间 + UTC 偏移的 ISO-8601 字符串，
例如 `2026-10-25T10:42:00+08:00`，界面不显示。服务端允许省略或传 `null`（旧客户端）；
有值时必须可解析且带时区偏移，否则返回 HTTP 422。原偏移保留写入记录的 `submittedAt`
字段，同键再次提交随整条记录替换。

## 版本信息

客户端每次提交自动附带三个版本字段，界面不显示，用于判断反馈时设备上的数据是否已过期
（策略 `outdated` 为 `false` 时，App 已在表单处禁止落后数据上传）：

- `appVersion`：App `versionName(versionCode)`，如 `0.2.5(6)`
- `dbVersion`：设备当前 transit.db 版本（`yyyyMMddHHmmss`，UTC+8）；`0` 表示未知（旧版本升级而来）
- `rulesVersion`：设备当前生效的 line_rules.json 版本（内置与在线取较新者）

均可省略或传 `null`（旧客户端）；有值时须为 1–64 个 `[0-9A-Za-z._+()-]` 字符，否则 HTTP 422。
写入记录同名字段并出现在日志中，同键再次提交随整条记录替换。

## 测试

安装开发依赖 pytest 后，在仓库根目录运行 `python -m pytest server/tests`。
服务运行时无需安装第三方包。
