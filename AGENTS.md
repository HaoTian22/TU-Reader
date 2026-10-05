# TU-Reader

NFC 交通卡读取器（交通联合卡 / 深圳通 / 岭南通 / 苏州 / 天津 / 住建部 CU）。业务/协议细节见 README.md。

## 构建

本机无 gradle wrapper jar、无 JAVA_HOME，直接用缓存 Gradle 构建：

```bash
export JAVA_HOME="C:/Users/Hao_T/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" && export PATH="$JAVA_HOME/bin:$PATH"
/c/Users/Hao_T/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle :app:assembleDebug --console=plain
```

## 两套数据库

| 库 | Room | 内容 | 位置 |
|---|---|---|---|
| 站名映射库 `transit.db` | `AppDatabase`（version=5） | city / protocol_city_code / line / station / reader_device | `assets/data/transit.db` → 私有 databases/ |
| 用户库 `user_data.db` | `UserDatabase` | cards / raw_records / transactions_archive | 应用私有目录 |

站名/线路在应用内以数据库 ID（stationId/lineId）传递，名称按界面语言即时解析，不持久化中文名。

## transit.db 生命周期与在线更新

- **数据源**：`D:\Code\Android\APPs-Dev\tripreader-data` CSV → `tools/update_transit_db.py` 增量更新内置库（保留已有英文名/坐标/线路配色；CSV `Station-en` 列为英文名权威，非空且与库中不同则同步 `station.station_name_en`），并维护 `room_master_table.identity_hash`。
- **发布**：`tools/update_transit_db.py --upload` 在更新后自动调 `tools/upload_transit_db.py`（纯 Python SigV4）上传到 Cloudflare R2（`https://assets2.haotian22.top/transit.db`，对象键 `transit.db`）。R2 凭据从 `tools/.r2config.json`（已被 .gitignore 排除）或环境变量 `R2_*` 读取；上传前校验 identity_hash 与 App schema 一致，防止发布 App 会拒绝的库。
- **内置**：`assets/data/transit.db` 首次启动经 `createFromAsset` 拷贝。
- **在线整库更新**（设置页「站名映射表更新」）：
  1. `data/StationDbUpdater.kt` — 从 `https://assets2.haotian22.top/transit.db` 下载到 cache（改 URL 改 `DOWNLOAD_URL`）。
  2. `data/db/AppDatabase.kt#replaceWithDownloaded` — **先以当前 schema 打开下载库校验 identity_hash**（不匹配/非 SQLite 抛异常，原库不动）→ 关旧实例、清旧库（含 WAL）、替换文件、重置单例。
  3. `data/TransitData.kt#reload` — 清空内存索引并重建（运行期缓存，`ensureLoaded` 双检锁）。
  4. `MainViewModel.updateStationDatabase()` / `reloadDisplayLanguage()` — 协程编排 + 结果状态（`stationDbUpdating` / `stationDbUpdateStatus`），已选卡片站名按新库重解析。

## 关键约束

`AppDatabase` 当前为 `version=5`，服务端 DB 必须与 App schema 的 identity_hash 一致（当前 `6faef85bfabe32fba63ee2cb3c1a496e`）。**transit.db 不做应用内迁移：版本或 identity_hash 不一致时清除本地映射库及 UI 缓存，从 asset 重建；user_data.db 等用户数据库继续保留 Room Migration。任何 transit schema 升级必须同步升级 App 版本、内置库及服务端库**，否则在线更新会被拒绝。`reader_device` 的唯一键是 `(device_code, transit_type)`；数据库导入、纠错、撤销及反馈存储必须按这个组合定位。更新/删除已命中的行可按 `device_id` 操作，不能仅按编号批量覆盖。

`city` 以 `assets/data/citylist.csv` 为标准 TU 城市目录，仅收录城市级代码（含州/地区、直辖市及港澳），当前 339 条。省级及区县代码不收录，不能仅按代码末尾 0 判断行政级别。设备、线路和站点的 `city_id` 按来源 CSV 的省份/城市目录确定；原始 `device_code` 保留用于匹配分桶。`protocol_city_code(protocol, code, city_id)` 解释非标准/别名代码，允许 TU、CU、YCT；不能从网络代码推断已匹配设备的实际城市。Python 更新工具只接受当前发版 schema，保留已有 ID、坐标、英文名及配色，不转换旧结构；应用重建后从独立保存的 overrides 文件重新导入纠错。

深圳 TU 轨道交通的终端号与 CU 共用 `Guangdong/Shenzhen/cu.csv`，原始编号前缀为 `5180`，实际城市为标准 TU `5840`。协议筛选必须保留这项共享规则（包括 TU 终端匹配、0x18 兜底匹配和命中后的实际城市解析）；其他地区的 CU 映射不能混入 TU。

同城标准别名天津 1121→1100、南京 3018→3010、香港 9101→0344、澳门 9102→0446 均通过 `protocol_city_code` 解析，城市选择器仅保留主代码。

## 反馈服务（server/）

站名纠错公开上传端点（`FeedbackUploader` → `POST /v1/overrides`，URL 为 `BuildConfig.FEEDBACK_UPLOAD_URL`）。**纯标准库 http.server，零第三方依赖**（此前 FastAPI 栈占 ~40MB+ 私有内存，现 ~17MB，docker-compose 另有 64MB 硬上限）。数据仅落 `server/data/overrides.json`（Docker volume）：按 `device_code|transit_type` 定位，每条记录包含 `csv` 字符串（`Prefix,Code,Type,Line,Station`，无表头）及协议、地区元数据。按全新服务器部署，无旧格式迁移。格式见 `server/README.md`。校验语义集中在 `app.py#parse_override`；测试 `python -m pytest server/tests`（仅 pytest 一个开发依赖）。

反馈上传的 `locationSource` 必填，仅允许 `AUTO`（本次纠错保留自动填充的地区）或 `MANUAL`（本次纠错输入/重新选择过地区）。两处纠错入口都跟踪城市编辑动作；只改线路或站名保持 `AUTO`，手动选回原城市仍为 `MANUAL`。客户端内部 `LocationSource` 的定位精度枚举继续用于地图/行程，上传使用独立的 `FeedbackLocationSource`。

> 记忆：build 环境见 `memory/build-env-java-gradle.md`；transit.db 来源/更新见 `memory/transit-db-source.md`、`memory/transit-db-ota-update.md`。
