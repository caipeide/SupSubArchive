# 排障笔记

面向维护者与深度使用者。现象 → 原因 → 处理，按「拉取」「同步」「离线」「构建/真机」分组。

## 拉取

**拉取很慢（84 个请求跑了 40 秒）** —— 这是设计值，不是卡住。限速器固定 2 req/s、突发 3。确认方法：`logcat` 里搜 `RateLimit`，命中行会算出等待量。

**报 429 或「请求过于频繁」** —— 说明有并发绕过了桶，或有人在外部并发调用同一账号。检查 `RateLimitPolicy` 的 `penaltyMs`（默认 15s）是否被调小；429 分支会惩罚并尊重 `Retry-After`。

**关注点内容拉不到** —— 主界面「拉取设置 → 包含关注点内容」是总开关，关着就只拉订阅源（网站 / 公众号 / X）。另外 `countUnreadByType` 的统计要用 `type = all`；服务端 `type = unread` 表示「拉即标读」，会消费云端未读。

**完成页出现「去重报告」** —— URL 或正文重复，被直接丢弃且不落本地。这是刻意的：判重时以关注的身份为准，避免同一篇在多个关注点下各存一份。

## 同步

**同步完 Notion 没有新页** —— 按顺序自查：

1. 本地库是不是空的？本地库被清空后**不会**从 Notion 拉回来重建（`pendingForSync` 为空直接跳过，`notion_page_id` 为空时 pull 直接 return）。
2. `logcat` 搜 `RateLimit` 之外的 `Pull` / `Sync` 标签，看 `warnings` 有没有收集到失败项——部分失败不会整体报错。
3. 分库是不是新发现的？新分库必须走 `since = 0` 全量拉，否则被 `on_or_after(last_pull_ts - 120s)` 过滤掉。

**Notion 总有旧页重复** —— push 前用隐藏列 `PROP_CONTENT_ID` 查已有页（`findExistingPageByContentId`）。如果你的 Notion 库是手工建的、没有这一列，就会重复建页。

**已读 / 高价值双向不同步** —— 两者都是 LWW：比 `local_edited_at` 与 `notion_last_edited_at`，后写者胜。若本地改了没推上去，看脏标记列（`user_read_dirty` / `high_value_dirty`）；`setHighValue` 会走 `markPropResyncIfSynced`（`SYNCED → PENDING_PROP`）。同一字段交替被两边改时，以「后同步完成的那一方」为准，多轮静止后不会翻转。

**删除的条目又冒出来了** —— 删除是真同步删 + 写墓碑（`synced_deleted` / `PROP_DELETED`）。`pendingForSync()` 必须排除 `DELETED_*`，否则墓碑会被反复重推。

**分库概览数字不对** —— 概览的 Notion 端文章数是联网翻页统计出来的，有 350ms 限速，没拉完就退出会少算。

**「来源分类」出现两个订阅源** —— 历史坑：pull 方向把 select 的中文 label 直接赋给 `origin_category`，push 方向却按英文 key 翻译，口径不一致把 GROUP BY 切碎了。现在 `OriginCategory.fromKey` 双向解析（先 key、后中文 label 兜底），`ArchiveStore.stats.byOrigin` 消费侧也按 `fromKey(k).key` 归一。

## 离线

**联网按钮全是灰的** —— 当前处于离线模式。等网络恢复，App 的 `ConnectivityManager` 会监听并在有 INTERNET 时自动 `refreshUserInfo()` 退出；想立刻重试，点顶部横幅的「重试连接」。

**离线时账号信息空白** —— 账号基础字段缓存进 `SharedPreferences`，正常应有值。如果没有，说明上一次在线时也没缓存成功，联网一次即可。

**离线时点拉取没反应** —— 有意为之：`requestPull` / `startMarkRead` / `startNotionSync` / `probeSubscriptions` 都前置了 `_isOffline` 护栏。

## 阅读页

**关注点未读统计把已读条目也算进去了** —— 历史 bug（筛序错误），已修。

**跑马灯卡在滚动** —— 列表滚动时会暂停跑马灯（P0），长时间不动才继续；实现上用 offset 平移而非重排（P1），避免每帧重排导致的卡顿。

**平板竖屏变回单栏** —— 双栏判定用 `LocalConfiguration` 的 `smallestScreenWidthDp >= 600 && screenWidthDp >= 720`。如果用了 window-size-class，平板竖屏会被判成单栏。

**弹窗顶部状态栏图标看不清** —— 弹窗跑在独立 Dialog 窗口，改主 Activity 的 `WindowInsetsController` 无效。必须从弹窗内容区的 `LocalView` → `(view.parent as? DialogWindowProvider)?.window` → `WindowCompat.getInsetsController(...)` 设置 `isAppearanceLightStatusBars`。浅色弹窗背景配深色图标（true），深色弹窗配浅色（false）。

**`Modifier.weight(1f)` 报类型错误** —— 别 `import androidx.compose.foundation.layout.weight`，Row / Column 作用域内直接调用即可，扩展会解析错。

## 构建与真机

```bash
cd android
./gradlew assembleDebug                 # 产物 app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` 不入库（机器相关），CI 里需要先配置 `sdk.dir`。
- 改完 `versionCode` / `versionName` 必须重装 APK 才生效；`applicationId` 保持不变，重装会丢本地库——先「导出备份 (.db)」。
- ColorOS（PKJ110）自动化时，**底部导航点不中**（被手势区拦），改用 TAB 键（keyevent 61 / 66）做焦点导航；页面按钮滚到 y ≈ 1450–1600 才点得到。
- 调试单个同步时用过 `files/debug_single_sync.txt` 开关，**用完务必删掉**，否则日常同步只会处理一篇。
- 「同步拉取」也是个开关：开了会让拉取按不同策略跑，排障前先确认它没被留着。

## 日志怎么抓

```bash
$ANDROID_HOME/platform-tools/adb logcat -c
$ANDROID_HOME/platform-tools/adb logcat | grep -iE "RateLimit|Pull|Sync|Notion"
```

提 issue 时请附上：版本号（`versionName`）、系统版本、上面这段日志、以及复现步骤。
