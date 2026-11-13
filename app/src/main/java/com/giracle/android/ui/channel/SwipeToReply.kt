package com.giracle.android.ui.channel

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.giracle.android.ui.common.AppIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * メッセージを右へすべらせると返信する (スマホのチャットでよくある操作。D-043)。
 * しきい値 (64dp) を越えたところで手応え (振動) を返し、指を離すと [onReply] を呼んで元の位置へ戻る。
 * 横の動きだけを拾うので、一覧の縦のスクロールや長押しの邪魔はしない。[enabled] が false なら何もしない
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    if (!enabled) {
        Box(content = content)
        return
    }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reply by rememberUpdatedState(onReply)
    Box {
        // 引いた分だけ、左に返信のアイコンが現れる
        Icon(
            AppIcons.Reply, null,
            Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(22.dp)
                .graphicsLayer { alpha = (offset.value / threshold).coerceIn(0f, 1f) },
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    var passed = false
                    // 引いた量は手元で積む (offset への反映は後で動くので、offset.value から足すと動きが抜けることがある)
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { passed = false; acc = offset.value },
                        onDragEnd = {
                            if (passed) reply()
                            scope.launch { offset.animateTo(0f) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, dx ->
                        val next = (acc + dx).coerceIn(0f, threshold * 1.4f)
                        acc = next
                        if (!passed && next >= threshold) {
                            passed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (passed && next < threshold) passed = false
                        change.consume()
                        scope.launch { offset.snapTo(next) }
                    }
                },
            content = content,
        )
    }
}
