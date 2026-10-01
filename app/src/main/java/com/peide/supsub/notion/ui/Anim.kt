package com.peide.supsub.notion.ui
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全局动效参数。
 *
 * 时长分三档、缓动统一用 [FastOutSlowInEasing]，保证整个 App 的动作节奏一致：
 * 元素消失永远比出现快（FAST 出、NORMAL 进），符合 Material Motion 的「离场干脆、入场从容」。
 */
object Anim {
    /** 小范围变化：淡出、颜色/数值过渡 */
    const val FAST = 150

    /** 常规：卡片展开收起、状态切换、进度条 */
    const val NORMAL = 250

    /** 大范围：对话框、底部面板等占屏元素 */
    const val SLOW = 400

    val Easing: Easing = FastOutSlowInEasing
}

fun <T> tweenFast(): TweenSpec<T> = tween(Anim.FAST, easing = Anim.Easing)

fun <T> tweenNormal(): TweenSpec<T> = tween(Anim.NORMAL, easing = Anim.Easing)

fun <T> tweenSlow(): TweenSpec<T> = tween(Anim.SLOW, easing = Anim.Easing)

/** 卡片/面板展开：淡入 + 自顶部纵向展开 */
val CardEnter: EnterTransition =
    fadeIn(animationSpec = tweenNormal()) +
        expandVertically(animationSpec = tweenNormal(), expandFrom = Alignment.Top)

/** 卡片/面板收起：淡出（更快）+ 向顶部收拢 */
val CardExit: ExitTransition =
    fadeOut(animationSpec = tweenFast()) +
        shrinkVertically(animationSpec = tweenNormal(), shrinkTowards = Alignment.Top)

/** 横幅类元素：淡入 + 轻微上滑，避免生硬弹出 */
val BannerEnter: EnterTransition =
    fadeIn(animationSpec = tweenNormal()) +
        slideInVertically(animationSpec = tweenNormal()) { it / 6 } +
        expandVertically(animationSpec = tweenNormal(), expandFrom = Alignment.Top)

val BannerExit: ExitTransition = CardExit

/**
 * 状态机切换（Idle → Running → Done）的过渡：
 * 新内容淡入 + 极轻微放大，旧内容先淡出，容器高度用 [SizeTransform] 平滑过渡。
 */
fun statusTransform(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(
        animationSpec = tween(Anim.NORMAL, delayMillis = Anim.FAST, easing = Anim.Easing),
    ) + scaleIn(animationSpec = tweenNormal(), initialScale = 0.96f),
    initialContentExit = fadeOut(animationSpec = tweenFast()),
    sizeTransform = SizeTransform(clip = false) { _, _ -> tweenNormal() },
)

/**
 * 带进出场动画的对话框容器。
 *
 * 系统 [Dialog] 默认没有内容过渡，这里用 [MutableTransitionState] 让内容
 * 淡入 + 轻微放大（0.92 → 1），关闭时先播完淡出再真正移除窗口。
 */
@Composable
fun MotionDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    // 关闭时保持窗口存在，直到退出动画播完
    if (visible || transitionState.currentState || !transitionState.isIdle) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(animationSpec = tweenNormal()) +
                    scaleIn(animationSpec = tweenNormal(), initialScale = 0.92f),
                exit = fadeOut(animationSpec = tweenFast()) +
                    scaleOut(animationSpec = tweenFast(), targetScale = 0.96f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 420.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) { content() }
                }
            }
        }
    }
}
