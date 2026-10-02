# 公开纠错服务

服务使用 Python 标准库，启动命令为 `python app.py`；Docker 部署继续使用
`docker compose up -d --build`。端点为 `GET /health` 和 `POST /v1/overrides`。

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

## 测试

安装开发依赖 pytest 后，在仓库根目录运行 `python -m pytest server/tests`。
服务运行时无需安装第三方包。
