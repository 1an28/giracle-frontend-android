package com.giracle.android.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 見た目はそのままで、押せる範囲を 48dp 以上にする (UI_PRINCIPLES.md の P-4、#68、D-055)。
 *
 * Compose が小さい要素の押せる範囲を自動で 48dp に広げるのは、すぐ横や真下に別の押せる要素が無いときだけ
 * (`TouchHitBehaviorTest`)。一覧の上に浮くボタンや、隣の行と接する小さい要素は取られるので、押す処理そのものを 48dp 以上の箱に付ける。
 * [content] に渡す Modifier (押したときの波紋) は、見た目の形で切り取った後に付ける (波紋が見た目の外に出ないように)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PressArea(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    content: @Composable (visual: Modifier) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .combinedClickable(interactionSource = source, indication = null, role = role, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(Modifier.indication(source, ripple())) }
}

/**
 * Material 3 の SmallFloatingActionButton と同じ見た目 (高さ 40dp) で、押せる範囲は周り 4dp を足した 48dp 以上にしたもの。
 * SmallFloatingActionButton は押せる範囲を広げず、一覧の上に浮かべると見た目の外は下の行に取られる (#68)。
 * 見た目の位置を保つため、置く側は外側の余白と間隔をそれぞれ 4dp ずつ減らす。
 */
@Composable
fun SmallFab48(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PressArea(onClick, modifier, role = Role.Button) { visual ->
        val color = FloatingActionButtonDefaults.containerColor
        Surface(Modifier.padding(4.dp), shape = FloatingActionButtonDefaults.smallShape, color = color, contentColor = contentColorFor(color), shadowElevation = 6.dp) {
            Box(visual.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp), contentAlignment = Alignment.Center) { content() }
        }
    }
}
