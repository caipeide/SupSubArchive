# 贡献指南

欢迎提 issue 和 PR。这个仓库是一个人维护的，所以流程尽量简单，但有几条底线。

## 先说环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 35 / minSdk 29（Android 10+） |
| AGP / Kotlin / Gradle | 8.5.2 / 2.0.20 / 8.9 |

`local.properties` 里的 `sdk.dir` 不入库，本机配好就行，不用提交。

```bash
cd android
./gradlew assembleDebug
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 提 issue

**动手改之前，先搜一下有没有同类 issue。** 有效的 issue 至少包含：

1. 版本号（`versionName`）+ 系统版本（含平板 / 手机的宽度差异）
2. 复现步骤，最好 3 步以内说得清
3. 现象截图，或 `adb logcat | grep -iE "RateLimit|Pull|Sync|Notion"` 的相关片段
4. 期望与实际各一句话

涉及同步的 issue，请确认三件事：**本地库没被清空**、Notion 端没有手工重复建页、分库（按天）是 App 内建的而非手工创建。

## 提 PR

- **先开 issue 聊方向**，尤其是 UI 改动——界面改法通常有多种取舍，讨论清楚比直接改一半再返工省事。
- 提交前 `./gradlew assembleDebug` 必须通过；合成到 UI 的改动建议附一张真机截图。
- 一条 commit 一个改动，`feat:` / `fix:` / `perf:` / `refactor:` / `docs:` 前缀。
- **不要**顺手改 `versionCode` / `versionName`，版本号统一由维护者发布时改。
- 涉及本地数据库的改动，迁移必须写成 `if (oldVersion < N) xxx(db)` 的幂等形式，并保证旧数据能升上来。
- 涉及 Notion 写操作的改动，先说明对已有页面是「更新」还是「重建」——重建会丢属性历史。
- PR 会触发 GitHub Actions 构建，构建失败（红灯）的 PR 不会合并。

## 代码结构

```
app/              界面与导航，Compose（包根=入口，ui/=组件，vm/=状态）
feature-supsub/   SupSub API、设备授权、拉取引擎、限速
feature-notion/   Notion 客户端、Schema、分库路由、同步引擎
core-data/        本地存储层（ArchiveDb / ArchiveStore / 聚类引擎）
```

跨模块调用请走公开接口，别跨模块直接摸内部类。`core-data` 是唯一写库的地方，别在别的模块里开 SQLite。

## 不要做的事

- 不要把令牌、Integration Token、容器页 ID 之类写进代码或测试资源。
- 不要往仓库里丢本地 `.db`、验证截图、dump 文件——`.gitignore` 已经拦了一批，不确定就先问。
- 不要动 `applicationId`，它决定你与正式版能否共存于同一设备。
