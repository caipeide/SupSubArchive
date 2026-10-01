# 安全策略

## 报告漏洞

**请不要用公开 issue 披露安全问题。** 请走 GitHub 的私密报告通道：

仓库页面 → **Security** → **Report a vulnerability**（Security Advisories）

我会尽快确认并回复；修复发布后，如果你愿意，会在 Release 说明里署名致谢。

## 本项目的安全边界

这是一个本地优先的客户端，架构上刻意把敏感面收窄：

| 数据 | 存放位置 | 是否出网 |
|---|---|---|
| SupSub access / refresh token | 设备端 `EncryptedSharedPreferences` | 仅随请求头发给 `supsub.net` |
| Notion Integration Token | 设备端本地存储 | 仅随请求头发给 `api.notion.com` |
| 归档正文 / 摘要 / 评论 | 应用私有目录下的 SQLite 单库 | 不出网（除非你主动开同步） |
| 导出的 `.db` 备份 | 你自行选定的目录 | 由你决定 |

唯一的出网目标是 `supsub.net` 与 `api.notion.com`。

## 属于受理范围的问题

- 令牌、归档内容被意外写入 logcat、导出文件或外部存储
- 导出的 `.db` 中包含超出预期的内容
- 明文传输、关闭证书校验、可被中间人伪造的请求
- 依赖项中已公开的高危漏洞（请在报告里给出 CVE 与影响路径）

## 不属于受理范围

- SupSub / Notion 服务端自身的安全问题，请向对应平台反馈
- 攻击者已取得设备本地文件读写权限（含 root）后的提权场景
- 使用他人令牌、绕过 SupSub 额度限制等滥用行为
- 单纯的功能缺陷或界面问题，请走 issue 模板

## 版本支持

项目处于 1.0.x 阶段，只对最新发布版本接受安全修复。
