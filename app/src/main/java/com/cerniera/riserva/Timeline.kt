package com.cerniera.riserva

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.withAlpha

/**
 * Today, midnight to midnight: the charge as a line, each stretch drawn in the colour of the screen
 * that was spending it. Champagne for the main screen, ivory for the cover, a faint line with the
 * screen off, green while charging. The steeper the line, the faster that screen drank.
 */
class TimelineView(c: Context) : View(c) {
    var samples: List<Sample> = emptyList()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(170))

    companion object {
        fun colour(s: Sample): Int = when {
            s.charging -> Kit.GOOD
            s.seat == Seat.MAIN -> Kit.CHAMPAGNE
            s.seat == Seat.COVER -> Kit.INK
            else -> withAlpha(Kit.MUTED, 0.6f)
        }
    }

    override fun onDraw(cv: Canvas) {
        val padL = context.px(4).toFloat(); val padB = context.px(22).toFloat(); val padT = context.px(8).toFloat()
        val w = width - padL * 2; val h = height - padB - padT
        val day = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun x(t: Long) = padL + w * ((t - day) / Store.DAY.toFloat()).coerceIn(0f, 1f)
        fun y(level: Int) = padT + h * (1f - level / 100f)
        // The grid: quarters of the day, and 50% faintly
        p.style = Paint.Style.STROKE; p.strokeWidth = context.px(1).toFloat(); p.color = Kit.HAIRLINE
        cv.drawLine(padL, y(0), padL + w, y(0), p)
        cv.drawLine(padL, y(50), padL + w, y(50), p)
        p.style = Paint.Style.FILL; p.textSize = context.px(11).toFloat(); p.typeface = Kit.sans(context, 450); p.color = Kit.MUTED
        for (q in 0..4) {
            val label = "%02d:00".format(q * 6)
            p.textAlign = when (q) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            cv.drawText(label, padL + w * q / 4f, height - context.px(5).toFloat(), p)
        }
        // Now
        val now = System.currentTimeMillis()
        p.color = withAlpha(Kit.CHAMPAGNE, 0.35f)
        cv.drawRect(x(now) - context.px(0.75f), padT, x(now) + context.px(0.75f), y(0), p)
        if (samples.size < 2) {
            p.textAlign = Paint.Align.CENTER; p.color = Kit.MUTED; p.textSize = context.px(13).toFloat()
            cv.drawText("Today's readings will draw here", width / 2f, padT + h / 2f, p)
            return
        }
        p.style = Paint.Style.STROKE
        samples.zipWithNext().forEach { (a, b) ->
            if (b.t - a.t > 16 * 3600_000L) return@forEach
            p.color = colour(a)
            p.strokeWidth = context.px(if (a.seat == Seat.OFF && !a.charging) 1.5f else 2.6f).toFloat()
            cv.drawLine(x(a.t), y(a.level), x(b.t), y(b.level), p)
        }
    }
}
