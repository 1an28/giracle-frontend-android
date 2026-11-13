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
 * (`TouchHitBehaviorTest` で確かめた。祖先の押せる要素より小さい要素が勝つが、すぐ横や真下の別の押せる要素には負ける)。なので「見た目が小さい」だけでは問題にせず、
 * 48dp に広げた範囲に、同じウィンドウのほかの押せる要素が入り込んでいる (= 実際に押せる範囲が 48dp に足りない) ものを数える。
 * 限界: 押す・長押しの操作を持たない要素 (引く操作だけのもの) は見ない。小さい要素どうしが狭い間で並び、
 * 広げた範囲どうしだけが重なる場合は取りこぼす。
 * Compose の Accessibility Test Framework の検査は、Robolectric では Compose の中身が見えず働かない (D-045) ので、
 * 意味情報 (semantics) を直接たどる。戻り値は足りない要素の説明 (見つからなければ空)。
 */
fun SemanticsNodeInteractionsProvider.smallTouchTargets(density: Density, minDp: Float = 48f): List<String> {
    val minPx = minDp * density.density

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

    fun ancestors(n: SemanticsNode) = generateSequence(n.parent) { it.parent }
    fun isAncestor(a: SemanticsNode, n: SemanticsNode) = ancestors(n).any { it.id == a.id }
    fun Rect.contains(r: Rect) = left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom

    val out = mutableListOf<String>()
    // ウィンドウ (シート・ダイアログ) ごとに別々に比べる。座標の基準がウィンドウごとに違い、下に隠れた画面とは取り合わないため
    for (root in onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()) {
        val pressable = mutableListOf<SemanticsNode>()
        fun visit(n: SemanticsNode) {
            val c = n.config
            if (c.getOrNull(SemanticsActions.OnClick) != null || c.getOrNull(SemanticsActions.OnLongClick) != null) pressable += n
            n.children.forEach(::visit)
        }
        visit(root)
        for (n in pressable) {
            // 大きさは切り取る前 (一覧の端で一部だけ見えている行も、本来の大きさ) で判定する
            val w0 = n.size.width.toFloat(); val h0 = n.size.height.toFloat()
            if (w0 + 0.5f >= minPx && h0 + 0.5f >= minPx) continue
            if (n.boundsInRoot.isEmpty) continue // 画面の外
            val pos = n.positionInRoot
            val cx = pos.x + w0 / 2; val cy = pos.y + h0 / 2
            val w = maxOf(w0, minPx); val h = maxOf(h0, minPx)
            val expanded = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            // 相手は、画面に見えている (切り取った後の) 範囲で比べる
            // n を囲む Surface など (意味情報で isContainer / isTraversalGroup の要素。Material3 の Surface は isContainer を付ける) が広げた範囲を覆っていれば、その下にある押せる層
            // (シートの後ろの背景など) は指を取らない (`TouchHitBehaviorTest.surfaceBlocksLayerBehind`)
            val shields = ancestors(n).filter { a ->
                @Suppress("DEPRECATION")
                val group = a.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true || a.config.getOrNull(SemanticsProperties.IsContainer) == true
                // スクロールする一覧 (LazyColumn) にも isTraversalGroup が付くが、指を通さない面ではない (上に重なるボタンが指を取る)
                val scrolls = a.config.getOrNull(SemanticsActions.ScrollBy) != null
                group && !scrolls && a.boundsInRoot.contains(expanded)
            }.toList()
            val clash = pressable.firstOrNull { o ->
                o.id != n.id && !related(n, o) && !o.boundsInRoot.isEmpty && o.boundsInRoot.overlaps(expanded) &&
                    shields.none { sh -> !isAncestor(sh, o) }
            }
            if (clash != null) {
                out += "${name(n)}: ${"%.0f".format(w0 / density.density)}x${"%.0f".format(h0 / density.density)}dp (${name(clash)} と近い)"
            }
        }
    }
    return out
}
