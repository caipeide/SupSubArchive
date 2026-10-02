package com.peide.supsub.notion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 项目内自绘图标。
 *
 * 下面这几枚图标只有 `material-icons-extended` 提供，但为了它们引入这个库，
 * 会把 2000+ 个图标全部打进 APK（单这一项就让 dex 膨胀约 20MB）。
 * 这里直接内置官方 path 数据（Material Symbols, Apache-2.0）替代；
 * 另外两处（Sync / ChevronRight）改用 `material-icons-core` 里已有的等价图标。
 */
private fun vectorIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.Black),
    ).build()

/** 书本 —— 用于底部导航「阅读」，替代 `Icons.AutoMirrored.Filled.MenuBook` */
val IconMenuBook: ImageVector by lazy {
    vectorIcon(
        "MenuBook",
        "M21 5c-1.11-.35-2.33-.5-3.5-.5-1.95 0-4.05.4-5.5 1.5-1.45-1.1-3.55-1.5-5.5-1.5S2.45 4.9 1 6v14.65c0 .25.25.5.5.5.1 0 .15-.05.25-.05C3.1 20.45 5.05 20 6.5 20c1.95 0 4.05.4 5.5 1.5 1.35-.85 3.8-1.5 5.5-1.5 1.65 0 3.35.3 4.75 1.05.1.05.15.05.25.05.25 0 .5-.25.5-.5V6c-.6-.45-1.25-.75-2-1zm0 13.5c-1.1-.35-2.3-.5-3.5-.5-1.7 0-4.15.65-5.5 1.5V8c1.35-.85 3.8-1.5 5.5-1.5 1.2 0 2.4.15 3.5.5v11.5z",
    )
}

/** 新窗口打开 —— 用于「查看原文」，替代 `Icons.AutoMirrored.Filled.OpenInNew` */
val IconOpenInNew: ImageVector by lazy {
    vectorIcon(
        "OpenInNew",
        "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
    )
}

/** 下载 —— 用于底部导航「拉取」，替代 `Icons.Filled.Download` */
val IconDownload: ImageVector by lazy {
    vectorIcon("Download", "M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z")
}

/** 已勾选复选框 —— 替代 `Icons.Filled.CheckBox` */
val IconCheckBox: ImageVector by lazy {
    vectorIcon(
        "CheckBox",
        "M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-9 14l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
    )
}

/** 未勾选复选框 —— 替代 `Icons.Filled.CheckBoxOutlineBlank` */
val IconCheckBoxOutlineBlank: ImageVector by lazy {
    vectorIcon(
        "CheckBoxOutlineBlank",
        "M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z",
    )
}

/** 空心星 —— 替代 `Icons.Filled.StarBorder` */
val IconStarBorder: ImageVector by lazy {
    vectorIcon(
        "StarBorder",
        "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
    )
}
