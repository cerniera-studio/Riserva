package com.cerniera.riserva

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha

/**
 * The gauge (16 Nov 2026): one tank of charge, and the two readings it gives, hours on the main
 * screen and hours on the cover. Spend either and both fall, because it is the same tank: a trip
 * computer's two figures from one fuel level. Beneath them the reserve lamp, dark most days, lit
 * rosso when the main screen has less than the chosen hours left.
 *
 * Upright, the tank stands at the left and the figures stack beside it. Wide, the tank lies along
 * the foot and the figures stand side by side above it. The same drawing makes the widget.
 */
object Gauge {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    /** [ink] and [muted] are the light's own colours; the liquid and the lamp are fixed. */
    fun draw(c: Context, cv: Canvas, w: Float, h: Float, e: Estimate, ink: Int, muted: Int, widget: Boolean = false) {
        val upright = h > w * 1.05f
        val u = minOf(w, h)
        if (upright) {
            val tankW = (w * 0.24f).coerceAtMost(u * 0.34f)
            tank(cv, RectF(0f, h * 0.04f, tankW, h * 0.96f), e, vertical = true, ink = ink)
            val x = tankW + w * 0.1f
            val big = (h * 0.13f).coerceAtMost((w - x) * 0.3f)
            var y = h * 0.1f
            y = figure(c, cv, x, y, "MAIN SCREEN", e.hoursMain, e.learnedMain, big, ink, muted)
            y += big * 0.55f
            y = figure(c, cv, x, y, "COVER SCREEN", e.hoursCover, e.learnedCover, big, ink, muted)
            y += big * 0.6f
            lamp(c, cv, x, y, big * 0.9f, e, ink, muted)
        } else {
            val tankH = (h * 0.2f).coerceIn(u * 0.12f, u * 0.3f)
            tank(cv, RectF(0f, h - tankH, w, h), e, vertical = false, ink = ink)
            val big = (h * 0.3f).coerceAtMost(w * 0.11f)
            val top = if (widget) h * 0.06f else h * 0.08f
            figure(c, cv, 0f, top, "MAIN", e.hoursMain, e.learnedMain, big, ink, muted)
            figure(c, cv, w * 0.42f, top, "COVER", e.hoursCover, e.learnedCover, big, ink, muted)
            // The lamp sits at the far end, above the tank's F
            lamp(c, cv, w * 0.82f, top + big * 0.25f, big * 0.8f, e, ink, muted, compact = true)
        }
    }

    /** The tank: a capsule filled to the charge, champagne, or rosso once in reserve. */
    private fun tank(cv: Canvas, box: RectF, e: Estimate, vertical: Boolean, ink: Int) {
        val rad = (if (vertical) box.width() else box.height()) / 2f
        val wall = rad * 0.12f
        p.shader = null; p.style = Paint.Style.FILL
        p.color = withAlpha(ink, 0.06f)
        cv.drawRoundRect(box, rad, rad, p)
        val inner = RectF(box).apply { inset(wall * 1.6f, wall * 1.6f) }
        val f = (e.level / 100f).coerceIn(0f, 1f)
        val liquid = RectF(inner)
        if (vertical) liquid.top = inner.bottom - inner.height() * f else liquid.right = inner.left + inner.width() * f
        val hi = if (e.reserve) 0xFFE8505A.toInt() else 0xFFF7E6C2.toInt()
        val lo = if (e.reserve) Kit.Fixed.ROSSO else 0xFFB8995F.toInt()
        // The paint still holds the tank's faint wash: the liquid must be fully opaque
        p.color = 0xFF000000.toInt()
        p.shader = if (vertical) LinearGradient(0f, liquid.top, 0f, liquid.bottom, hi, lo, Shader.TileMode.CLAMP)
            else LinearGradient(liquid.left, 0f, liquid.right, 0f, lo, hi, Shader.TileMode.CLAMP)
        val ir = (if (vertical) inner.width() else inner.height()) / 2f
        cv.save()
        val clip = android.graphics.Path().apply { addRoundRect(inner, ir, ir, android.graphics.Path.Direction.CW) }
        cv.clipPath(clip)
        cv.drawRect(liquid, p)
        // The meniscus: a fine bright line where the liquid meets the air
        p.shader = null; p.color = withAlpha(0xFFFFFFFF.toInt(), 0.55f)
        val m = wall * 0.9f
        if (vertical) cv.drawRect(liquid.left, liquid.top, liquid.right, liquid.top + m, p) else cv.drawRect(liquid.right - m, liquid.top, liquid.right, liquid.bottom, p)
        cv.restore()
        // The glass: a hairline, and E, half and F marked along it
        p.style = Paint.Style.STROKE; p.strokeWidth = wall; p.color = withAlpha(ink, 0.32f)
        val g = RectF(box).apply { inset(wall / 2, wall / 2) }
        cv.drawRoundRect(g, rad - wall / 2, rad - wall / 2, p)
        p.strokeWidth = wall * 0.8f; p.strokeCap = Paint.Cap.ROUND; p.color = withAlpha(ink, 0.4f)
        for (k in 1..3) {
            val t = k / 4f
            if (vertical) { val y = inner.bottom - inner.height() * t; cv.drawLine(box.right - rad * 0.55f, y, box.right - wall * 2.4f, y, p) }
            else { val x = inner.left + inner.width() * t; cv.drawLine(x, box.top + wall * 2.4f, x, box.top + rad * 0.55f, p) }
        }
        p.style = Paint.Style.FILL
    }

    /** An eyebrow, then the hours in Italiana. Returns the y below it. */
    private fun figure(c: Context, cv: Canvas, x: Float, top: Float, label: String, hours: Float?, learned: Float, big: Float, ink: Int, muted: Int): Float {
        p.shader = null; p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
        p.typeface = Kit.sans(c, 500); p.textSize = big * 0.24f; p.letterSpacing = 0.16f
        p.color = Kit.CHAMPAGNE
        var y = top + p.textSize
        cv.drawText(label, x, y, p)
        p.letterSpacing = 0f
        if (hours != null) {
            p.typeface = Kit.display(c); p.textSize = big; p.color = ink
            y += big * 1.02f
            cv.drawText(hm(hours), x, y, p)
        } else {
            // Still learning: how much use it has seen so far
            p.typeface = Kit.display(c); p.textSize = big * 0.62f; p.color = ink
            y += big * 0.75f
            cv.drawText("Learning", x, y, p)
            p.typeface = Kit.sans(c, 400); p.textSize = big * 0.24f; p.color = muted
            y += big * 0.38f
            cv.drawText(if (learned < 1f / 60) "no use seen yet" else "${hm(learned)} of use so far", x, y, p)
        }
        return y
    }

    /** The reserve lamp: an ember most days, lit rosso with a glow in reserve. */
    private fun lamp(c: Context, cv: Canvas, x: Float, top: Float, size: Float, e: Estimate, ink: Int, muted: Int, compact: Boolean = false) {
        val lw = size * 0.62f; val lh = size * 0.34f
        r.set(x, top, x + lw, top + lh)
        p.shader = null; p.style = Paint.Style.FILL
        if (e.reserve) {
            p.color = withAlpha(Kit.Fixed.ROSSO, 0.35f)
            val g = RectF(r).apply { inset(-lh * 0.45f, -lh * 0.45f) }
            cv.drawRoundRect(g, g.height() / 2, g.height() / 2, p)
            p.color = Kit.Fixed.ROSSO
        } else p.color = withAlpha(ink, 0.12f)
        cv.drawRoundRect(r, lh / 2, lh / 2, p)
        if (compact) return
        p.typeface = Kit.sans(c, 450); p.textSize = size * 0.22f; p.textAlign = Paint.Align.LEFT
        p.color = if (e.reserve) ink else muted
        val words = when {
            e.charging -> "Charging · ${e.level.toInt()}%"
            e.reserve -> "Reserve: stay folded"
            else -> "${e.level.toInt()}% in the tank"
        }
        cv.drawText(words, x + lw + size * 0.22f, top + lh / 2 + p.textSize * 0.36f, p)
    }
}

/** The gauge as a view, for the app. */
class GaugeView(c: Context) : View(c) {
    var estimate: Estimate? = null
        set(v) { field = v; contentDescription = v?.let { "Main screen ${hm(it.hoursMain)}, cover screen ${hm(it.hoursCover)}, ${it.level.toInt()} percent" + if (it.reserve) ", reserve" else "" }; invalidate() }

    // Given a height by its pane, it takes it; in the cover's single column it asks for 300 dp
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h)
            else (300 * resources.displayMetrics.density).toInt().let { if (MeasureSpec.getMode(h) == MeasureSpec.AT_MOST) minOf(it, MeasureSpec.getSize(h)) else it }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val e = estimate ?: return
        Gauge.draw(context, canvas, width.toFloat(), height.toFloat(), e, Kit.INK, Kit.MUTED)
    }
}
