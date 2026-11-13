package com.giracle.android

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.unit.Density

/**
 * 押せる要素の押せる範囲が、決めた大きさ (既定 48dp。Android 公式の基準) に足りているかを調べる (D-045 の P-4)。
 *
 * Compose は、見た目が 48dp より小さい押せる要素の押せる範囲を、48dp まで自動で広げる
 * (広げた範囲が重なったところは、指に近い方が取る)。なので「見た目が小さい」だけでは問題にせず、
 * 48dp に広げた範囲に、ほかの押せる要素が入り込んでいる (= 実際に押せる範囲が 48dp に足りない) ものを数える。
 * Compose の Accessibility Test Framework の検査は、Robolectric では Compose の中身が見えず働かない (D-045) ので、
 * 意味情報 (semantics) を直接たどる。戻り値は足りない要素の説明 (見つからなければ空)。
 */
fun SemanticsNodeInteractionsProvider.smallTouchTargets(density: Density, minDp: Float = 48f): List<String> {
    val minPx = minDp * density.density
    val pressable = mutableListOf<SemanticsNode>()
    fun visit(n: SemanticsNode) {
        val c = n.config
        if (c.getOrNull(SemanticsActions.OnClick) != null || c.getOrNull(SemanticsActions.OnLongClick) != null) pressable += n
        n.children.forEach(::visit)
    }
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::visit)

    fun name(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.TestTag)
        ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
        ?: "(名前なし)"

    // 祖先・子孫の関係にある押せる要素 (行全体と、行の中のボタンなど) は、指に近い方が取るので重なりとして数えない
    fun related(a: SemanticsNode, b: SemanticsNode): Boolean {
        var p = a.parent; while (p != null) { if (p.id == b.id) return true; p = p.parent }
        p = b.parent; while (p != null) { if (p.id == a.id) return true; p = p.parent }
        return false
    }

    val out = mutableListOf<String>()
    for (n in pressable) {
        val b = n.boundsInRoot
        if (b.width + 0.5f >= minPx && b.height + 0.5f >= minPx) continue
        val w = maxOf(b.width, minPx); val h = maxOf(b.height, minPx)
        val expanded = Rect(b.center.x - w / 2, b.center.y - h / 2, b.center.x + w / 2, b.center.y + h / 2)
        val clash = pressable.firstOrNull { o -> o.id != n.id && !related(n, o) && o.boundsInRoot.overlaps(expanded) && !o.boundsInRoot.isEmpty }
        if (clash != null) {
            out += "${name(n)}: ${"%.0f".format(b.width / density.density)}x${"%.0f".format(b.height / density.density)}dp (${name(clash)} と近い)"
        }
    }
    return out
}
