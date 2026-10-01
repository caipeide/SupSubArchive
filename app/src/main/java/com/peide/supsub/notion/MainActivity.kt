package com.peide.supsub.notion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peide.supsub.api.DeviceCodeResponse
import com.peide.supsub.api.UserInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.peide.supsub.notion.ui.*
import com.peide.supsub.notion.vm.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 浅色 UI → 状态栏图标用深色，保证时间/信号/电池可见
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AuthViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val dirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化 URI 权限，避免重启后失效
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.onExportDirSelected(uri)
        } else {
            Toast.makeText(context, "未选择导出目录", Toast.LENGTH_SHORT).show()
        }
    }

    // 首次启动 SAF 时默认打开 Documents 目录，减少用户操作
    val initialDir = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                DocumentsContract.buildDocumentUriUsingTree(
                    DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Documents"
                    ),
                    "primary:Documents"
                )
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { statusTransform() },
                contentKey = {
                    when (it) {
                        is AuthUiState.LoggedOut -> 0
                        is AuthUiState.Loading -> 1
                        is AuthUiState.AwaitingAuthorization -> 2
                        is AuthUiState.LoggedIn -> 3
                        is AuthUiState.Error -> 4
                    }
                },
                label = "auth-state",
                modifier = Modifier.fillMaxWidth(),
            ) { s ->
                when (s) {
                    is AuthUiState.LoggedOut -> LoggedOutSection(onLogin = vm::startLogin)

                    is AuthUiState.Loading -> LoadingSection()

                    is AuthUiState.AwaitingAuthorization -> AwaitingSection(
                        state = s,
                        onCopy = { context.copyToClipboard(s.deviceCode.userCode) },
                        onOpen = { context.openAuthPage(s.deviceCode) },
                        onCancel = vm::cancelLogin,
                    )

                    is AuthUiState.LoggedIn -> LoggedInSection(
                        vm = vm,
                        user = s.user,
                        offline = s.offline,
                        onPull = {
                            vm.requestPull()
                        },
                        onChangeDir = {
                            vm.changeExportDir { initialUri -> dirLauncher.launch(initialUri ?: initialDir) }
                        },
                        onProbe = {
                            vm.probeSubscriptions { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                    )

                    is AuthUiState.Error -> ErrorSection(
                        message = s.message,
                        onRetry = vm::startLogin,
                        onBack = vm::cancelLogin,
                    )
                }
            }

            if (vm.usingPlainStorage) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "提示：当前设备加密存储不可用，令牌以普通方式保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 已登录主界面。
 *
 * 信息层级自上而下三段：
 * 1. 账号与归档状态（我是谁、本地攒了多少内容）
 * 2. 拉取设置（拉哪些来源、拉哪段时间）
 * 3. 执行（拉取、标记已读、同步 Notion）
 */
/** 三个底部分页：拉取 / 阅读 / 同步（设置页已合并进「拉取」）。默认进入「拉取」。 */
private enum class MainTab { PULL, READ, SYNC }

@Composable
private fun LoggedInSection(
    vm: AuthViewModel,
    user: UserInfo,
    offline: Boolean = false,
    onPull: () -> Unit,
    onChangeDir: () -> Unit,
    onProbe: () -> Unit,
) {
    // 默认停留在「拉取」页；用 rememberSaveable 让旋转/重建后保留当前页
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.PULL) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (offline) OfflineBanner(onRetry = vm::refreshUserInfo)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (selectedTab) {
                MainTab.PULL -> PullTab(
                    vm = vm,
                    user = user,
                    offline = offline,
                    onPull = onPull,
                    onChangeDir = onChangeDir,
                    onProbe = onProbe,
                )
                MainTab.READ -> ReadTab(vm = vm)
                MainTab.SYNC -> SyncTab(vm = vm, offline = offline)
            }
        }
        FloatingNavBar(selectedTab = selectedTab, onSelect = { selectedTab = it })
    }
}

/** 离线模式横幅：提示当前仅可阅读/标记本地内容，联网操作不可用，并提供「重试连接」 */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "离线模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "仅可阅读、标记本地内容；拉取 / 云端标记已读 / Notion 同步需联网后使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onRetry) { Text("重试连接") }
        }
    }
}

/** 底部悬浮分页栏：圆角 + 阴影 + 左右留白的药丸样式，紧凑高度 */
@Composable
private fun FloatingNavBar(selectedTab: MainTab, onSelect: (MainTab) -> Unit) {
    val items = listOf(
        Triple(MainTab.PULL, "拉取", Icons.Filled.Download),
        Triple(MainTab.READ, "阅读", Icons.AutoMirrored.Filled.MenuBook),
        Triple(MainTab.SYNC, "同步", Icons.Filled.Sync),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (tab, label, icon) ->
                val selected = selectedTab == tab
                val color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 通用：每个分页的可滚动内容容器（独立滚动，互不干扰） */
@Composable
private fun TabScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// ─── 拉取分页（合并原设置页）：账号 + 本地归档 + 拉取设置 + 执行 + 聚类 ───

@Composable
private fun PullTab(
    vm: AuthViewModel,
    user: UserInfo,
    offline: Boolean = false,
    onPull: () -> Unit,
    onChangeDir: () -> Unit,
    onProbe: () -> Unit,
) {
    val includeFocus by vm.includeFocus.collectAsState()
    val enabledSourceTypes by vm.enabledSourceTypes.collectAsState()
    val subscriptionCountByType by vm.subscriptionCountByType.collectAsState()
    val unreadByType by vm.unreadByType.collectAsState()
    val focusCloudUnread by vm.focusCloudUnread.collectAsState()
    val archiveStats by vm.archiveStats.collectAsState()
    // 本地已归档的关注点文章数：与「本地归档·来源分类·关注点」完全一致，
    // 避免直接展示服务端偏大的 unreadCount 造成与归档/阅读页对不上的困惑
    val localFocusArchived = archiveStats?.byOrigin?.get("FOCUS") ?: 0
    val archiveHint by vm.archiveHint.collectAsState()
    val archiveValidating by vm.archiveValidating.collectAsState()
    val probeRunning by vm.probeRunning.collectAsState()
    val countsRefreshing by vm.countsRefreshing.collectAsState()
    val pullState by vm.pullState.collectAsState()
    val focusMarkState by vm.focusMarkState.collectAsState()
    val sourceMarkState by vm.sourceMarkState.collectAsState()
    val markReadScopeHint by vm.markReadScopeHint.collectAsState()
    val exportDir by vm.exportDir.collectAsState()
    val backupState by vm.backupState.collectAsState()
    val clusterState by vm.clusterState.collectAsState()
    val pullStrategy by vm.pullStrategy.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val strategySummary = pullStrategySummary(pullStrategy, subscriptions)

    var strategySheetVisible by remember { mutableStateOf(false) }

    TabScaffold {
        Spacer(Modifier.height(4.dp))
        GroupLabel("账号与归档")
        AccountCard(user = user, onRefresh = vm::refreshUserInfo, onLogout = vm::logout)
        ArchiveStatusCard(
            stats = archiveStats,
            hint = archiveHint,
            validating = archiveValidating,
            onRevalidate = vm::validateArchive,
        )
        Spacer(Modifier.height(4.dp))
        GroupLabel("拉取设置")
        SourceSettingsCard(
            includeFocus = includeFocus,
            enabledSourceTypes = enabledSourceTypes,
            subscriptionCountByType = subscriptionCountByType,
            unreadByType = unreadByType,
            focusCloudUnread = focusCloudUnread,
            localFocusArchived = localFocusArchived,
            onToggleIncludeFocus = vm::setIncludeFocus,
            onToggleSourceType = vm::setSourceTypeEnabled,
            onProbe = onProbe,
            probing = probeRunning,
            countsRefreshing = countsRefreshing,
        )
        SectionCard(
            title = "拉取策略",
            subtitle = "默认只拉未读；也可以指定订阅源与时间范围",
        ) {
            PullStrategyRow(
                summary = strategySummary,
                isCustom = pullStrategy != null,
                onEdit = { strategySheetVisible = true },
                onReset = { vm.setPullStrategy(null) },
            )
        }
        Spacer(Modifier.height(4.dp))
        GroupLabel("执行")
        PullActionCard(
            pullState = pullState,
            exportDir = exportDir,
            backupState = backupState,
            strategySummary = strategySummary,
            focusMarkState = focusMarkState,
            sourceMarkState = sourceMarkState,
            markReadScopeHint = markReadScopeHint,
            onPull = onPull,
            onMarkRead = vm::startMarkRead,
            onCancelPull = vm::cancelPull,
            onChangeDir = onChangeDir,
            onBackup = vm::exportBackup,
            offline = offline,
        )
        ClusterSectionCard(
            state = clusterState,
            onRecluster = vm::recluster,
            onReset = vm::resetClusterState,
        )
    }

    PullStrategySheet(
        visible = strategySheetVisible,
        subscriptions = subscriptions,
        includeFocus = includeFocus,
        current = pullStrategy,
        onConfirm = vm::setPullStrategy,
        onReset = { vm.setPullStrategy(null) },
        onDismiss = { strategySheetVisible = false },
    )
}

// ─── 同步分页：同步到 Notion ───

@Composable
private fun SyncTab(vm: AuthViewModel, offline: Boolean = false) {
    TabScaffold {
        Spacer(Modifier.height(4.dp))
        GroupLabel("同步到 Notion")
        NotionSyncCard(vm = vm, offline = offline)
    }
}

/** 分组小标题，用来在长页面里建立视觉层级 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
    )
}

@Composable
private fun AccountCard(user: UserInfo, onRefresh: () -> Unit, onLogout: () -> Unit) {
    SectionCard(
        title = user.name.ifBlank { "SupSub 账号" },
        subtitle = user.email.ifBlank { "—" },
        trailing = { TextButton(onClick = onRefresh) { Text("刷新") } },
    ) {
        // 状态药丸单独一行，避免与「退出登录」挤在同一行导致换行/错位
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatPill(
                text = if (user.expired) "套餐已过期" else "套餐有效",
                container = if (user.expired) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (user.expired) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            StatPill("到期 ${formatEpochSeconds(user.endAt)}")
        }
        Spacer(Modifier.height(10.dp))
        // 退出登录独占整行并右对齐
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onLogout) {
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LoggedOutSection(onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("登录 SupSub 账号", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "使用设备授权登录：App 会显示一个短码，你在打开的网页里确认即可，无需在手机上输入密码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onLogin) { Text("开始登录") }
    }
}

@Composable
private fun LoadingSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "正在处理…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AwaitingSection(
    state: AuthUiState.AwaitingAuthorization,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    var opened by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("在网页中输入此码完成授权", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.deviceCode.userCode,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 6.sp,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onCopy) { Text("复制短码") }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                opened = true
                onOpen()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (opened) "重新打开授权页" else "打开授权页") }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "等待授权中… 剩余 ${state.remainingSeconds}s",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
private fun ErrorSection(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("出错了", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("重试") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun ClusterSectionCard(
    state: ClusterUiState,
    onRecluster: () -> Unit,
    onReset: () -> Unit,
) {
    SectionCard(
        title = "相似文章聚类",
        subtitle = "把跨公众号 / 改写的同主题文章归并到同一「聚类主题」，并同步到 Notion 对应的 select 属性",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onRecluster,
                enabled = state !is ClusterUiState.Running,
            ) { Text(if (state is ClusterUiState.Running) "聚类中…" else "全量重聚类") }
        }
        // 状态提示独占一行、左对齐，避免在按钮右侧被挤压换行
        when (state) {
            is ClusterUiState.Done ->
                Text(
                    "完成：形成 ${state.clusters} 个簇，重排 ${state.reassigned} 篇",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            is ClusterUiState.Error ->
                Text(
                    "失败：${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            is ClusterUiState.Running ->
                Text(
                    "正在聚类…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            else -> {}
        }
        if (state is ClusterUiState.Done) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onReset, modifier = Modifier.align(Alignment.Start)) { Text("收起") }
        }
    }
}

// ─── 辅助 ────────────────────────────────────────────────────

private fun formatEpochSeconds(seconds: Long): String {
    if (seconds <= 0) return "—"
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(seconds * 1000))
}

private fun Context.copyToClipboard(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("SupSub 授权码", text))
    Toast.makeText(this, "已复制：$text", Toast.LENGTH_SHORT).show()
}

/** 优先用带码的完整授权链接，用户点一下确认就行，省去手输 */
private fun Context.openAuthPage(code: DeviceCodeResponse) {
    val url = code.verificationUriComplete.ifBlank { code.verificationUri }
    if (url.isBlank()) {
        Toast.makeText(this, "服务端未返回授权页地址", Toast.LENGTH_LONG).show()
        return
    }
    try {
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
    } catch (_: Throwable) {
        Toast.makeText(this, "无法打开浏览器，请手动访问：$url", Toast.LENGTH_LONG).show()
    }
}
