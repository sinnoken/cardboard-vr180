package com.example.vr180.vrui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.vr180.render.M3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 選單項目。數值類用 Stepper：控制器左右鍵調整，視線則看著 − / + 再按鍵。 */
sealed class MenuItem {
    data class Button(val label: () -> String, val action: () -> Unit) : MenuItem()
    data class Stepper(
        val label: String,
        val value: () -> String,
        val dec: () -> Unit,
        val inc: () -> Unit,
    ) : MenuItem()

    /**
     * 進度列。控制器左右鍵呼叫 step(±1)；看著列上某個位置按確認就跳到那裡（seekTo 收到 0..1）。
     * fraction 小於 0 代表還不知道長度。
     */
    data class Seek(
        val label: () -> String,
        val fraction: () -> Float,
        val step: (dir: Int) -> Unit,
        val seekTo: (Float) -> Unit,
    ) : MenuItem()
}

class MenuPage(
    val title: String,
    val hint: () -> String,
    val rows: List<List<MenuItem>>,
    val onBack: () -> Unit,
    val showStatus: Boolean = false,
)

enum class NavAction { UP, DOWN, LEFT, RIGHT, SELECT, BACK }

/**
 * 世界固定的 VR 選單面板。
 * 所有方法都必須在 GL 執行緒呼叫（由 VrRenderer.frameHook 與 glView.queueEvent 驅動）。
 * 面板畫在 Bitmap 上（Android Canvas，可直接用中文字型），再當成貼圖交給著色器。
 */
class VrMenu {
    private val bmpW = 1200
    private val bmpH = 1000
    val bitmap: Bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    var visible = false
        private set
    var page: MenuPage? = null
        private set

    // 面板在世界中的位置
    val f = floatArrayOf(0f, 0f, -1f)
    val r = floatArrayOf(1f, 0f, 0f)
    val u = floatArrayOf(0f, 1f, 0f)
    val half = floatArrayOf(tan(Math.toRadians(27.0)).toFloat(), 0f).also { it[1] = it[0] * 1000f / 1200f }

    private data class Cell(val item: MenuItem, val rect: RectF, val row: Int, val col: Int)
    private enum class Zone { NONE, DEC, INC }

    private var cells = listOf<Cell>()
    private var focus = 0
    private var gazeCell: Int? = null
    private var gazeZone = Zone.NONE
    private var gazeSeek = -1f          // 視線在進度列上的位置 0..1，不在進度列上是 -1

    // 面板剛放到視線前方時，面板中心可能剛好落在某一格上（例如主選單的「設定…」）。
    // 先忽略那一格，等視線移到別格才開始搶焦點，打開選單就按確認仍然是原本焦點的項目。
    private var gazeArmed = true
    private var gazeStart: Int? = null
    private var gazeStartSet = false
    private var dirty = true

    /** 播放狀態等外部文字，任一執行緒都可以設定。 */
    @Volatile var status: String = ""

    fun show(p: MenuPage, head: FloatArray) {
        page = p
        layout()
        focus = 0
        if (!visible) placeInFront(head)
        visible = true
        dirty = true
    }

    /** 換頁但保持面板位置。 */
    fun open(p: MenuPage) {
        page = p
        layout()
        focus = 0
        dirty = true
    }

    fun hide() {
        visible = false
        page = null
        dirty = true
    }

    /** 暫時收起面板，保留目前頁面與焦點（校正時看清楚測試圖樣）。 */
    fun conceal() {
        visible = false
        dirty = true
    }

    /** conceal() 收起的面板。 */
    val concealed get() = !visible && page != null

    /** 重新顯示 conceal() 收起的面板，放回視線前方。 */
    fun reveal(head: FloatArray, pitchDeg: Float?) {
        if (page == null) return
        placeInFront(head, pitchDeg)
        visible = true
        dirty = true
    }

    fun invalidate() { dirty = true }

    /**
     * 把面板放到視線前方。pitchDeg 為 null 時跟著目前視線；
     * 給定角度（例如 -36°）時放在水平前方偏下，讓正中央的校正圖樣不被擋住。
     */
    fun placeInFront(head: FloatArray, pitchDeg: Float? = null) {
        gazeArmed = false
        gazeStartSet = false
        val fw = M3.apply(head, 0f, 0f, -1f)
        // 面板保持直立：右方向取水平面上的向量
        var rx = -fw[2]; var rz = fw[0]
        val n = sqrt(rx * rx + rz * rz)
        if (n < 1e-3f) { rx = 1f; rz = 0f } else { rx /= n; rz /= n }
        val rr = floatArrayOf(rx, 0f, rz)
        val ff = if (pitchDeg == null) fw else {
            val p = Math.toRadians(pitchDeg.toDouble())
            val hx = rz; val hz = -rx            // 水平前方 = up × right
            floatArrayOf((hx * cos(p)).toFloat(), sin(p).toFloat(), (hz * cos(p)).toFloat())
        }
        System.arraycopy(normalize(ff), 0, f, 0, 3)
        System.arraycopy(rr, 0, r, 0, 3)
        System.arraycopy(normalize(cross(rr, f)), 0, u, 0, 3)
    }

    /** 每幀呼叫：視線落在哪個項目上。回傳 true 表示面板需要重新上傳。 */
    fun onFrame(head: FloatArray): Boolean {
        if (visible) {
            val g = M3.apply(head, 0f, 0f, -1f)
            val df = dot(g, f)
            var hit: Int? = null
            var zone = Zone.NONE
            var seek = -1f
            if (df > 0.1f) {
                val px = dot(g, r) / df / half[0]
                val py = dot(g, u) / df / half[1]
                if (abs(px) <= 1f && abs(py) <= 1f) {
                    val bx = (px * 0.5f + 0.5f) * bmpW
                    val by = (0.5f - py * 0.5f) * bmpH
                    val idx = cells.indexOfFirst { it.rect.contains(bx, by) }
                    if (idx >= 0) {
                        hit = idx
                        val c = cells[idx]
                        if (c.item is MenuItem.Seek) {
                            // 量化到 1/200，視線微動時不必每幀重畫
                            seek = (((bx - c.rect.left - seekInset) / (c.rect.width() - 2 * seekInset)).coerceIn(0f, 1f) * 200f).toInt() / 200f
                        }
                        if (c.item is MenuItem.Stepper) {
                            zone = when {
                                bx < c.rect.left + c.rect.width() * 0.22f -> Zone.DEC
                                bx > c.rect.right - c.rect.width() * 0.22f -> Zone.INC
                                else -> Zone.NONE
                            }
                        }
                    }
                }
            }
            if (!gazeArmed) {
                if (!gazeStartSet) { gazeStart = hit; gazeStartSet = true }
                if (hit != gazeStart) gazeArmed = true
                else { hit = null; zone = Zone.NONE; seek = -1f }
            }
            // 視線移到「另一個」項目時才搶走焦點，避免蓋掉控制器的選擇
            if (hit != gazeCell) {
                gazeCell = hit
                if (hit != null) focus = hit
                dirty = true
            }
            if (zone != gazeZone) { gazeZone = zone; dirty = true }
            if (seek != gazeSeek) { gazeSeek = seek; dirty = true }
        }
        // 面板收起時不畫也不上傳貼圖（播放中每秒都會 invalidate）；dirty 留著，下次顯示時再畫
        if (!dirty || !visible) return false
        draw()
        dirty = false
        return true
    }

    /** 控制器（或 Cardboard 按鈕）輸入。回傳是否已處理。 */
    fun handle(a: NavAction): Boolean {
        val p = page ?: return false
        if (!visible) return false
        if (cells.isEmpty()) return false
        val cur = cells[focus.coerceIn(cells.indices)]
        when (a) {
            // 上下鍵依閱讀順序走到上一格／下一格：同一列有兩個數值項目時（例如 k1、k2），
            // 左右鍵已經拿去調數值，只有這樣右邊那格才走得到
            NavAction.UP, NavAction.DOWN -> {
                focus = (focus + if (a == NavAction.UP) -1 else 1).coerceIn(cells.indices)
                gazeZone = Zone.NONE
            }
            NavAction.LEFT, NavAction.RIGHT -> {
                val item = cur.item
                if (item is MenuItem.Stepper) {
                    if (a == NavAction.LEFT) item.dec() else item.inc()
                } else if (item is MenuItem.Seek) {
                    item.step(if (a == NavAction.LEFT) -1 else 1)
                } else {
                    val next = cells.withIndex().filter { it.value.row == cur.row }
                        .firstOrNull { it.value.col == cur.col + if (a == NavAction.LEFT) -1 else 1 }
                    if (next != null) focus = next.index
                }
            }
            NavAction.SELECT -> when (val item = cur.item) {
                is MenuItem.Button -> item.action()
                // 視線的 − / + 區只對「視線所在的那一格」有效，避免視線在 A、焦點在 B 時調到 B
                is MenuItem.Stepper -> when (if (gazeCell == focus) gazeZone else Zone.NONE) {
                    Zone.DEC -> item.dec()
                    Zone.INC -> item.inc()
                    Zone.NONE -> {}
                }
                // 只有視線正落在進度列上才跳，焦點在進度列但視線在別處時不動
                is MenuItem.Seek -> if (gazeCell == focus && gazeSeek >= 0f) item.seekTo(gazeSeek)
            }
            NavAction.BACK -> p.onBack()
        }
        dirty = true
        return true
    }

    /** 視線是否正落在某個項目上（Cardboard 按鈕據此決定要「選取」還是「關閉選單」）。 */
    fun isGazingAtItem() = visible && gazeCell != null

    /** 目前焦點是否在進度列上（按住左右時要節流）。 */
    fun focusedIsSeek(): Boolean =
        visible && cells.isNotEmpty() && cells[focus.coerceIn(cells.indices)].item is MenuItem.Seek

    /** 目前焦點是否在數值項目上（按住左右時才加速）。 */
    fun focusedIsStepper(): Boolean =
        visible && cells.isNotEmpty() && cells[focus.coerceIn(cells.indices)].item is MenuItem.Stepper

    // ---------------- 版面與繪製 ----------------

    private val pad = 36f
    private val titleH = 84f
    private val hintH = 190f
    private val rowH = 82f
    private val gap = 10f
    private val seekInset = 24f   // 進度列左右內縮，視線換算與繪製共用

    private fun layout() {
        val p = page ?: return
        val list = ArrayList<Cell>()
        var y = pad + titleH + hintH
        p.rows.forEachIndexed { ri, row ->
            val w = (bmpW - 2 * pad - gap * (row.size - 1)) / row.size
            row.forEachIndexed { ci, item ->
                val x = pad + ci * (w + gap)
                list += Cell(item, RectF(x, y, x + w, y + rowH - gap), ri, ci)
            }
            y += rowH
        }
        cells = list
    }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 22, 24, 30) }
    private val cellBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 48, 52, 62) }
    private val focusBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 40, 98, 180) }
    private val zoneBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 230, 160, 40) }
    private val trackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 90, 95, 108) }
    private val trackFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val seekTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val zoneHintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 230, 160, 40) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 52f; typeface = Typeface.DEFAULT_BOLD
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 205, 215); textSize = 30f
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER
    }

    private fun draw() {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val p = page ?: return
        canvas.drawRoundRect(RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()), 36f, 36f, bg)
        canvas.drawText(p.title, pad, pad + 52f, titlePaint)

        // 狀態（含錯誤訊息）放在說明前面：超過 5 行時被截掉的是說明，不是錯誤訊息
        val hint = listOf(if (p.showStatus) status else "", p.hint())
            .filter { it.isNotBlank() }.joinToString("\n")
        val sl = StaticLayout.Builder.obtain(hint, 0, hint.length, hintPaint, (bmpW - 2 * pad).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(5)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        canvas.save()
        canvas.translate(pad, pad + titleH)
        sl.draw(canvas)
        canvas.restore()

        cells.forEachIndexed { i, c ->
            val focused = i == focus
            canvas.drawRoundRect(c.rect, 18f, 18f, if (focused) focusBg else cellBg)
            val baseline = c.rect.centerY() + 13f
            when (val item = c.item) {
                is MenuItem.Button ->
                    canvas.drawText(item.label(), c.rect.centerX(), baseline, textPaint)
                is MenuItem.Stepper -> {
                    val zw = c.rect.width() * 0.22f
                    val left = RectF(c.rect.left, c.rect.top, c.rect.left + zw, c.rect.bottom)
                    val right = RectF(c.rect.right - zw, c.rect.top, c.rect.right, c.rect.bottom)
                    if (focused && gazeCell == i) {
                        // 視線在中間時按鍵不會有作用：把兩側淡淡標出來，提示要看著 − 或 + 才能調整
                        canvas.drawRoundRect(left, 18f, 18f, if (gazeZone == Zone.DEC) zoneBg else zoneHintBg)
                        canvas.drawRoundRect(right, 18f, 18f, if (gazeZone == Zone.INC) zoneBg else zoneHintBg)
                    }
                    canvas.drawText("−", left.centerX(), baseline, textPaint)
                    canvas.drawText("+", right.centerX(), baseline, textPaint)
                    canvas.drawText("${item.label}  ${item.value()}", c.rect.centerX(), baseline, textPaint)
                }
                is MenuItem.Seek -> {
                    canvas.drawText(item.label(), c.rect.centerX(), c.rect.top + 34f, seekTextPaint)
                    val bar = RectF(c.rect.left + seekInset, c.rect.bottom - 22f, c.rect.right - seekInset, c.rect.bottom - 12f)
                    canvas.drawRoundRect(bar, 5f, 5f, trackBg)
                    val frac = item.fraction()
                    if (frac >= 0f) {
                        val done = RectF(bar.left, bar.top, bar.left + bar.width() * frac.coerceIn(0f, 1f), bar.bottom)
                        canvas.drawRoundRect(done, 5f, 5f, trackFg)
                    }
                    if (gazeCell == i && gazeSeek >= 0f) {
                        val x = bar.left + bar.width() * gazeSeek
                        canvas.drawRect(x - 3f, c.rect.top + 6f, x + 3f, c.rect.bottom - 6f, zoneBg)
                    }
                }
            }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalize(a: FloatArray): FloatArray {
        val n = sqrt(dot(a, a)).coerceAtLeast(1e-6f)
        return floatArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }
}
