# 开发日志（历史归档，2026-08-12 及以前）

> 本文已降级为**历史开发日志**，不再随版本更新。
> 版本与功能现状请查看 [CHANGELOG.md](../CHANGELOG.md)，架构说明见 [architecture.md](architecture.md)。

| 项目 | 说明 |
|---|---|
| 应用名 | 订阅归档（SupSub → 本地模板 → Notion） |
| 包名 | `com.peide.supsub.notion` |
| 工程目录 | 仓库根 `android/`（早期在本地临时工程目录下开发） |
| 归档节点 | v2.0.0（2026-08-12 真机验证完成） |
| 最后更新 | 2026-08-12 |

---

## 一、里程碑总览

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 工程骨架（多模块 Gradle、Compose、可出 APK） | ✅ | `app` / `feature-supsub` / `feature-notion` / `core-data` |
| M1 SupSub 设备授权登录 + 令牌加密存储 + 自动续期 | ✅ | EncryptedSharedPreferences + 设备码 OAuth 流 |
| M2 订阅/文章拉取 → SAF 目录写 JSON 模板 | ✅ | Room 运行期已绕过（PKJ110 事务挂死），改用 SAF 直写 |
| M3 Notion 同步引擎（移植自「布导翁」） | ✅ | 真机已验证 Token + Database ID，可正常同步 |
| M4 完整 Compose UI（列表/详情/设置/精读） | 🟡 | 登录/账号/Notion 配置/拉取/归档列表已完成；精读详情待开发 |
| M5 WorkManager 定时自动同步 | ⬜ | 未开始 |
| M6 拉取与归档 UI 优化 + 标记已读重构 | ✅ | 四轮需求迭代完成，见下文 |
| M7 本地缓存 SQLite 单库 + Notion 半月分库（V2 重构） | ✅ | 2026-08-12 PKJ110 真机验证通过（拉取/同步/备份） |

---

## 二、版本迭代记录

### v2.0.0（2026-08-10）—— V2 架构：SQLite 单库 + Notion 半月分库

**动机**：每天近百篇新文章，本地 JSON 文件群膨胀、云端单一 Notion 库也越来越大。两处都做切分。

**本地缓存：JSON 文件群 → SQLite 单库**
- `core-data` 新增 `ArchiveDb`（SQLiteOpenHelper，WAL + `busy_timeout=5000`，放应用私有目录 `/data/data/com.peide.supsub.notion/files/archive-v2/supsub_archive.db`），三张表：`articles` / `notion_shards` / `meta`。
- `ArchiveStore` 封装去重插入、计数、筛选、标记已读/已同步、分片登记、备份导出（`backupTo` → SAF 单文档写 `Documents/SupSubArchive/backup/`）。
- V1 的 `FileExporter` / `LocalIndexProvider` / `IndexedArticleEntity`（Room 实体）整条链路删除；旧 JSON 归档保留在磁盘但 App 不再读取。
- `feature-supsub` 的 `PullEngine.pull()` 改为直写 SQLite，不再要求导出目录；`contentHashOf` 纳入 AI 维度。

**云端同步：单库 → 按半个月一个子库**
- `ShardKeys.of(publishedAt, capturedAt)`：1–15 日 → `YYYY-MM-H1`，16–31 日 → `YYYY-MM-H2`；以发布时间为准，缺发布时间退回入库时间。
- `NotionShardRouter` 三级查找（内存缓存 → 本地 `notion_shards` 表 O(1) → 容器页标题匹配防重复 → 创建），自愈重复建库。
- 容器页 ID 由用户在 App 里自行填写（形如 `<32 位十六进制>`），半月子库都建在它下面。
- `SupSubSyncEngine.sync()` 新增 `createdShards` 回传，UI「分库概览」展示各分片标题/条数/是否已建库。

**状态**：2026-08-12 已完成 PKJ110（Android 16）真机安装与端到端验证：
- 拉取 362 篇文章全部写入 SQLite，`shard_key=2026-08-H1`。
- Notion 同步自动创建半月子库「订阅归档 2026-08 上半月」（database_id 由接口返回），挂在容器页下；362 篇全部 `SYNCED` 并记录 `notion_page_id`；Notion API 查询确认数据库内共有 362 个 page。
- 备份导出成功生成 `supsub_archive_20260812_202318.db`（1412 KB）到 `Documents/SupSubArchive/SupSubArchive/backup/`，验证为完整可用 SQLite（含 articles/notion_shards/meta 三表）。
- 修复 Android 16 上 `SQLiteDatabase.execSQL` 拒绝 PRAGMA 的问题：`ArchiveDb.onOpen` 用 `rawQuery` 设置 `busy_timeout`；`ArchiveStore.backupTo` 用 `rawQuery` 执行 `wal_checkpoint(TRUNCATE)`。
- 列顺序优化：`NotionSchema.kt` 的 `newDatabaseProperties()` 调整顺序——新建半月子库时「已读 / 高价值」两列（MANUAL_PROPS）紧跟标题列（用户最常手动勾选）。**注意**：已建好的 `2026-08-H1` 子库因 `标签` 列已有 575 个选项、超出 Notion `updateDatabase` 单次 PATCH 的 100 选项上限，无法通过 API 安全重排（省略 options 会被 Notion 视为删除全部选项、连带清空 362 篇标签值），需用户在 Notion UI 内手动把这两列拖到标题右侧。

2026-08-10 已完成的 host 端 JVM 边界测试：半月分片路由逻辑 8/8 用例通过（含闰年、15/16 边界、capturedAt 兜底）。APK 28MB。

### v1.0.4（2026-08-02）—— 标记已读重构 + 拉取与归档 UI 优化

本版本对应四轮连续需求迭代，全部已完成、编译通过、真机验证。

#### 1. 拉取与归档：新增文章概况列表

需求：在「拉取与归档」模块查看已拉取文章的简要概况，展示订阅源、标题、发布时间、是否同步、是否本轮新增。

实现要点：

- 索引层新增分页查询接口 `listArticles(offset, limit)`，支持 Room 分页与 CachedFileIndex 内存排序切片。
- ViewModel 暴露 `archivedArticles`、`hasMore`、`loading`、`lastPulledContentIds`，提供 `refresh` / `loadMore`。
- 新建 `ArchivedArticlesCard.kt`，常驻展示在「拉取与归档」模块下方；重开 App 仍可查看。
- 列表行展示：文章标题、来源类型·名称药丸、发布时间、是否同步药丸、是否本次新增徽章。
- 修复索引实体缺少 `title` 字段的问题：`IndexedArticleEntity` 增加 `title`，Room 版本升到 3（破坏性迁移重建），CachedFileIndex 的 `index.json` 版本升到 2，使旧缓存失效并从 JSON 归档重建。

#### 2. 拉取与归档：常驻列表改为「入口按钮 + 底部弹窗 + 滚动条」

需求：常驻卡片内容多时太长，改成带动画的弹出窗口，并加滚动条。

实现要点：

- `ArchivedArticlesCard` 改为入口卡片，显示「查看文章概况（N 篇）」按钮。
- 新增 `ArchivedArticlesSheet`，使用 `ModalBottomSheet`（底部上滑动画）。
- 内部 `LazyColumn` 展示全部文章，右侧配可拖动滚动条，底部显示「已显示 X 篇，可加载更多」。
- 弹窗标题从「本地已加载数」改为「归档总数」（如共 431 篇），避免与入口总数不一致。

#### 3. 标记已读：语义澄清与模块迁移

需求：

- 移除「拉取与归档」模块的标记已读入口，仅保留在 Notion 同步模块。
- 标记已读默认只标记本次同步的条目；增加子开关可改为「订阅源整源标记已读」。

实现要点：

- 移除 `PullSection.kt` 与 `MainActivity.kt` 中拉取模块的标记已读入口。
- Notion 同步模块新增 `MarkReadConfig` 组件：
  - 主开关「同步后自动标记已读」：同步到 Notion 成功后自动标记（默认关）。
  - 子开关「整源标记已读」（默认关）：
    - 关闭：只把本次同步成功的条目在**本地**标记为已读（云端未读不变，可重判，无风险）。
    - 开启：把本次拉取的订阅源在 supsub 云端**整源**标记为已读（含历史遗留未读，云端不可逆）。
  - 开启「整源」需二次确认弹窗，明确提示云端不可逆。
- 新增 `MarkReadStatusBanner` 展示自动整源标记的结果（Running / Done / Error）。
- 数据层支持按条目标记：`ArticleIndexDao` + `LocalIndex` 增加批量 `markRead(contentIds: Set<String>)`。
- `SupSubSyncEngine` 在 `SyncResult.Success/PartialFailure` 中回传 `syncedContentIds` 集合。
- ViewModel 调整：主开关开 + 整源关 → 本地批量标记；主开关开 + 整源开 → 云端整源 + 本地整源。

**关键约束**：supsub 云端 `markAsRead` 接口只支持按源（`MarkAsReadRequest(sourceType, sourceId)`），没有按文章接口。因此「只标记本次同步的条目」只能是**本地标记**，云端未读不变；整源模式才会真正改写云端状态。

---

### v1.0.5（2026-08-09）—— 单库 SAF 枚举修复 + 索引防清空

本版本修复了「回退分库改动」后遗留的单库同步空转问题（同步读到 0 篇），并顺手消除了一个会清空本地索引的隐患。

#### 1. 根因

OPPO PKJ110（Android 16）在当前的 SAF tree 授权下存在一个设备特有的坑：**单文件读取/写入都正常，但「列目录」枚举（`buildChildDocumentsUriUsingTree(...).query()`）会静默返回 0 条子项**。

- 同步第一步 `FileExporter.listItemDocuments(exportDir)` 去枚举 `items/` 目录 → 永远拿到 0 个文件 → 静默空同步。
- 索引层 `CachedFileIndex.loadLocked` 用枚举拿文件列表（得 0）→ 又用 `readRootFile` 读 `index.json`（内部同样靠枚举找文件，也得 0）→ 误判「索引过期」→ **用空集合把 752 条的 `index.json` 覆盖清空**（含 `notionPageId` / `syncStatus`）。

本地数据本身齐全：设备 `items/` 目录有 752 个 JSON，`index.json` 记录 752 条且全部 `SYNCED`。

#### 2. 修复方案

把 `FileExporter` 中所有「枚举目录」的写法，全部改为**按可预测的 docId 直接构造 URI 单文档读写**，彻底绕开坏枚举：

- 设备文件的 docId 可预测：`getDocumentId(exportDir)` = `primary:Documents/SupSubArchive/SupSubArchive`；
- item 文件 = `$docId/items/<contentId>.json`；`index.json` = `$docId/index.json`；
- 同步先读 `index.json` 拿到 752 个 contentId，再逐个直接构造 URI 读正文；
- 删除 `findChildByDisplayName` / `debugListChildren` 两个依赖坏枚举的辅助方法；
- `SupSubSyncEngine` 移除调试期临时插入的 VERIFY 日志。

#### 3. 改动文件

| 模块 | 文件 | 改动摘要 |
|---|---|---|
| `core-data` | `FileExporter.kt` | 移除 SAF 目录枚举，改用可预测 docId 直接构造 URI 单文档读写；删除 `findChildByDisplayName` / `debugListChildren` |
| `feature-notion` | `SupSubSyncEngine.kt` | 移除临时 VERIFY 日志 |

#### 4. 验证记录

| 验证项 | 结果 | 说明 |
|---|---|---|
| 编译 | ✅ | `./gradlew :app:assembleDebug` BUILD SUCCESSFUL |
| 安装 | ✅ | `adb install -r` 成功 |
| 扫描本地文章 | ✅ | 同步日志 `扫描到 752 篇本地文章`（修复前为 0） |
| 单文档读取 | ✅ | 752 个 item 文件 `readText` 全部成功，失败 0 次 |
| 同步决策 | ✅ | `待同步 28 篇（已跳过 724 篇）`，28 篇正文 hash 与 Notion 已存在页一致被短路跳过（符合预期） |
| 索引未被清空 | ✅ | 重拉设备 `index.json` 仍为 752 条 |
| Notion 单库 | ✅ | 「订阅资讯信息」页面完好（752 页，`has_more=true`） |
| 设备 | OPPO PKJ110 | serial `(已脱敏)` |

---

## 三、关键文件改动清单

| 模块 | 文件 | 改动摘要 |
|---|---|---|
| `core-data` | `IndexedArticleEntity.kt` | 增加 `title` 字段 |
| `core-data` | `AppDatabase.kt` | Room 版本 2 → 3，破坏性迁移 |
| `core-data` | `ArticleIndexDao.kt` | 新增 `listPaged`、`markRead(contentIds)` |
| `core-data` | `LocalIndex.kt` | 接口增加 `listArticles`、`markRead`；Room 与 CachedFileIndex 实现 |
| `core-data` | `CachedFileIndex.kt` | JSON 缓存版本 1 → 2；支持精确按 contentId 改写已读 |
| `feature-supsub` | `PullEngine.kt` | 收集 `PulledArticle`，`toIndex()` 带 title |
| `feature-notion` | `SupSubSyncEngine.kt` | `SyncResult` 回传 `syncedContentIds` |
| `app` | `AuthViewModel.kt` | 移除拉取模块标记逻辑；新增整源开关、本地按条目标记、JSON 兜底 |
| `app` | `MarkReadSection.kt` | 重写为 `MarkReadConfig` + `MarkReadStatusBanner`，含二次确认弹窗 |
| `app` | `NotionSyncCard.kt` | 接入新的标记已读配置组件 |
| `app` | `PullSection.kt` | 移除拉取模块标记已读入口 |
| `app` | `ArchivedArticlesCard.kt` | 入口卡片 + `ArchivedArticlesSheet` 弹窗、滚动条、分页 |
| `app` | `MainActivity.kt` | 移除拉取模块标记状态；新增归档弹窗状态 |

---

## 四、验证记录

| 验证项 | 结果 | 说明 |
|---|---|---|
| 编译 | ✅ | `./gradlew :app:assembleDebug` SUCCESSFUL |
| 安装 | ✅ | `adb install -r app-debug.apk` 成功 |
| 启动 | ✅ | monkey 启动无崩溃 |
| 设备 | OPPO PKJ110 | serial `(已脱敏)` |
| 文章概况列表 | ✅ | 入口显示「查看文章概况（431 篇）」；弹窗列表、滚动条、加载更多正常 |
| 标题显示 | ✅ | 修复 `title` 字段后不再显示「(无标题)」 |
| Notion 同步 | ✅ | Token 与 Database ID 配置后同步成功 |
| 标记已读 UI | ✅ | 主开关、子开关、二次确认弹窗均正常 |

验证截图归档位置：`archive/2026-08-02/screenshots/`

| 截图 | 内容 |
|---|---|
| `supsub_main.png` / `supsub_scroll.png` / `supsub_scroll2.png` | 拉取与归档主界面 |
| `s_main.png` | 设置/账号主界面 |
| `sheet.png` / `sheet2.png` / `sheet3.png` | 已拉取文章概况底部弹窗 |
| `notion.png` / `notion_on.png` | Notion 同步模块与标记已读开关 |
| `confirm.png` | 开启整源标记已读二次确认弹窗 |

---

## 五、已知约束

1. **订阅内容接口不返回正文全文**，只有 `summary`（摘要）。全文需走 deep-read（消耗不可逆的月度额度），因此产品策略为「批量同步只用摘要 + 对重点文章手动精读」。
2. **整源标记已读（`mark-as-read`）不可逆**：调用前必须二次确认，UI 已做风险提示。
3. **按条目标记已读仅在本地生效**，不会同步到 supsub 云端，因为云端接口只支持按源标记。
4. **Room 运行期未实例化**：部分机型（如 OPPO PKJ110）出现 Room 事务挂死，已绕过；本地索引主要依赖 SAF 直写的 JSON 归档 + `CachedFileIndex`。
5. **PKJ110 上 SAF「列目录」枚举静默返回 0 子项**：`buildChildDocumentsUriUsingTree(...).query()` 连授权根目录都返回空。所有本地读写已改为按可预测 docId 直接构造 URI 单文档访问，**禁止再用 `findFile` / 目录枚举**，否则会复现「同步读 0 篇 / 索引被清空」。

---

## 六、下一步计划

| 优先级 | 事项 | 状态 |
|---|---|---|
| P0 | M5 WorkManager 定时自动同步 | ⬜ 未开始 |
| P1 | M4 精读详情页（文章正文/摘要/源链接查看） | ⬜ 未开始 |
| P2 | 列表项长按多选 / 批量同步 / 批量标记已读 | ⬜ 未开始 |
| P3 | 异常处理与重试机制增强（网络中断、Notion API 限流） | ⬜ 未开始 |
| P4 | 引入单元测试与 UI 测试 | ⬜ 未开始 |

---

## 七、构建与安装

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 产物路径：`app/build/outputs/apk/debug/app-debug.apk`
