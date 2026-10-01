# 架构说明

面向维护者，讲清楚数据在哪、怎么流、冲突怎么解。只想用 App 的话，看 README 就够了。

## 一、整体拓扑

手机、平板、Notion 三者是**星型**，设备之间不直连：

```
   SupSub API          Notion API
        │                   │
        ▼                   ▼
   feature-supsub     feature-notion
        │                   │
        └─────────┬─────────┘
                  ▼
             core-data（本地 SQLite 单库）
                  ▼
                  app（Compose UI）
```

**本地库是唯一真相源**，Notion 只是远端镜像，不是备份。因此：本地库被清空后，同步不会把 Notion 的内容「拉回来」重建——当前设计不提供 Notion 全量导入。

## 二、本地存储

`core-data` 用 `SQLiteOpenHelper` 手写（`ArchiveDb`），不用 Room。当前 `VERSION = 15`，6 张表：

| 表 | 作用 |
|---|---|
| `articles` | 文章主体，含 `focus_id` / `focus_title`（关注点）、`origin_category`、`content_id`、同步字段 |
| `notion_shards` | 分库（按天）元信息与水印 |
| `meta` | 键值配置（令牌缓存、备份目录、分库发现状态等） |
| `article_history` | 拉取 / 同步历史 |
| `article_comments` | 评论同步 |
| `synced_deleted` | 删除墓碑 |

- 数据库名：`supsub_archive.db`，WAL 模式。
- 迁移一律写成 `if (oldVersion < N) xxx(db)` 的幂等形式，任何时候重复执行都安全。
- `published_at` 全系统按**分钟**存（秒截断），与 Notion 的 date 精度对齐。

## 三、拉取

1. `feature-supsub` 的 `PullEngine` 按订阅源并发拉取，`SupsubClient` 上挂 `RateLimitInterceptor`。
2. 限速是**令牌桶负记账**：桶空时把欠账写成负数预扣，refill 先还债再累积。这样多点并发不会各自算出同样的等待时间后一起抢令牌（实测能把 2 req/s 严格跑出来，而不是被服务端限流放大到 7~8 req/s）。
   策略集中在 `RateLimitPolicy`：`perSecond = 2.0`、`burst = 3`、`penaltyMs = 15s`；429 或「请求过于频繁」→ 惩罚并抛 `rateLimited`（尊重 `Retry-After`）。
3. 重试：`callWithRetry` 指数退避（1s → 2s → 4s），429 / 5xx / IO 可重试。
4. 判重：URL / 内容重复直接丢弃，**不落本地**，并记一条 `DroppedItem` 到完成页的「去重报告」。`focus_id` 只记首个关注点。
5. 关注点**增量早停**：连续 3 页 contentId 全命中本地即停（首次全量拉取不受影响）。

## 四、同步

**先拉后推**，以本地 `articles` 为唯一源。

- 拉取门禁：`remoteTime < local.notionLastEditedAt`（用 `<` 而非 `<=`，规避 Notion 分钟精度）。
- 分库发现：**每次双向同步都重新发现**分库（容器页强制重走，REPLACE 幂等）；新发现的分库走 `since = 0` 全量拉，否则会被 `on_or_after(last_pull_ts - 120s)` 过滤掉一篇都拉不到。
- 防重复建页：push 前用 `findExistingPageByContentId` 按隐藏列 `PROP_CONTENT_ID` 找已有页。
- 删除：写 `PROP_DELETED` + 墓碑，`pendingForSync()` 必须排除 `DELETED_*`。
- 已读 / 高价值：`PROP_HIGH_VALUE` 是受管双向列，`setHighValue` 后走 `markPropResyncIfSynced`（`SYNCED → PENDING_PROP` 属性级重推），与已读同走 LWW（`local_edited_at` vs `notion_last_edited_at`）。
- 冲突：同字段后写者胜；脏标记列（`user_read_dirty` / `high_value_dirty`）驱动增量推送，只有脏的或新建页才写字段，多轮同步能收敛到同一状态。

## 五、离线

- 冷启动 `getUserInfo()` 网络失败时，若本地有令牌 → 进入 `LoggedIn(offline = true)`，**不强制回登录页**；没有令牌才报错。
- 联网操作（`requestPull` / `startMarkRead` / `startNotionSync` / `probeSubscriptions`）全部前置 `_isOffline` 护栏。
- `ConnectivityManager` 监听恢复，离线 → 有 INTERNET 时自动 `refreshUserInfo()` 退出离线。
- 账号基础字段（name / plan / expiresAt）缓存进 `SharedPreferences`，离线时不会卡空白。

## 六、UI 结构

`app` 模块分三层：包根放 `MainActivity`（应用入口与各 Tab 的私有 composable），`ui/` 放 Tab 与卡片组件，`vm/` 放 `AuthViewModel` 及各状态机（`AuthUiState` / `PullUiState` / `FocusMarkUiState` / …）。四个 Tab：设置 / 拉取 / 阅读 / 同步，底部浮动导航，默认落在拉取页。

- 阅读页双栏判定：`smallestScreenWidthDp >= 600 && screenWidthDp >= 720`（用 `LocalConfiguration`，不用 window-size-class，避免平板竖屏回落单栏）。
- 缩放比：`rememberSaveable { mutableFloatStateOf(null) }`，null 走默认（宽屏 38/62，窄屏 44/56）。
- 状态栏图标：弹窗跑在独立 Dialog 窗口，改主 Activity 的 `WindowInsetsController` 无效，必须从弹窗内容区的 `LocalView` 往上找到 `DialogWindowProvider` 再设。

## 七、目录

```
SubSup/
├── app/              界面与导航
│   └── …/notion/     入口 MainActivity + ui/（Tab 与卡片）+ vm/（AuthViewModel）
├── feature-supsub/   SupSub API、设备授权、拉取引擎、限速
├── feature-notion/   Notion 客户端、Schema、分库路由、同步引擎
├── core-data/        本地存储层
├── docs/             架构 / 排障 / 截图 / 赞赏码
└── README.md  CHANGELOG.md  LICENSE  CONTRIBUTING.md
```
