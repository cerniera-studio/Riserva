package com.cerniera.riserva

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit

class RiservaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this, "Riserva")
    }
}

/**
 * A reading from Fold Taskbar's meter (16 Nov 2026): kept, then the widget and the reserve lamp
 * brought up to date. Only numbers arrive here, and they stay on the phone.
 */
class Reading : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ACTION) return
        val seat = Seat.of(i.getStringExtra("state")) ?: return
        val s = Sample(i.getLongExtra("t", System.currentTimeMillis()), seat, i.getIntExtra("level", -1),
            i.getIntExtra("charge", 0), i.getIntExtra("current", 0), i.getBooleanExtra("charging", false))
        if (s.level !in 0..100) return
        Store.add(c, s)
        Prefs.setLastMeter(c, s.t)
        refresh(c, s)
    }

    companion object {
        const val ACTION = "com.cerniera.riserva.READING"

        /** Brings the widget and the lamp up to date from the latest reading. */
        fun refresh(c: Context, latest: Sample) {
            val e = Model.estimate(c, latest)
            GaugeWidget.updateAll(c, e)
            Reserve.check(c, e)
        }
    }
}

/**
 * The reserve lamp as a quiet notification, posted only as it lights and withdrawn as it goes
 * out. Because it comes from a Cerniera app, Fold Taskbar's Spia shows it as a champagne lamp.
 */
object Reserve {
    private const val CHANNEL = "reserve"
    private const val ID = 7

    fun check(c: Context, e: Estimate) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val was = Prefs.lit(c)
        if (e.reserve == was) return
        Prefs.setLit(c, e.reserve)
        if (!e.reserve) { nm.cancel(ID); return }
        if (!Prefs.tell(c)) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reserve lamp", NotificationManager.IMPORTANCE_LOW).apply {
            description = "When the main screen has little time left"
        })
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val cover = e.hoursCover?.let { " Folded, about ${hm(it)} on the cover." } ?: ""
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(Kit.Fixed.ROSSO)
            .setContentTitle("Riserva: about ${hm(e.hoursMain)} on the main screen")
            .setContentText("Stay folded to stretch it.$cover")
            .setStyle(Notification.BigTextStyle().bigText("Stay folded to stretch it.$cover"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, n) }
    }
}

/**
 * The widget (16 Nov 2026): the gauge drawn at the widget's own size, one picture for each way
 * round, so turning the phone shows a picture made for that shape. Visconti glass, always.
 */
class GaugeWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = updateAll(c, null)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = updateAll(c, null)

    companion object {
        fun updateAll(c: Context, given: Estimate?) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, GaugeWidget::class.java))
            if (ids.isEmpty()) return
            val e = given ?: Model.estimate(c, Sample.now(c, Store.last(c)?.seat ?: Seat.COVER))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            ids.forEach { id ->
                val o = m.getAppWidgetOptions(id)
                val d = c.resources.displayMetrics.density
                val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).coerceAtLeast(60)
                val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW).coerceAtLeast(minW)
                val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80).coerceAtLeast(40)
                val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH).coerceAtLeast(minH)
                // Upright the widget is narrowest and tallest; turned, widest and shortest
                val shapes = linkedSetOf(SizeF(minW.toFloat(), maxH.toFloat()), SizeF(maxW.toFloat(), minH.toFloat()))
                val views = shapes.associateWith { size ->
                    RemoteViews(c.packageName, R.layout.gauge).apply {
                        setImageViewBitmap(R.id.picture, picture(c, (size.width * d).toInt(), (size.height * d).toInt(), e))
                        setContentDescription(R.id.picture, "Riserva: main screen ${hm(e.hoursMain)}, cover ${hm(e.hoursCover)}")
                        setOnClickPendingIntent(R.id.frame, open)
                    }
                }
                runCatching { m.updateAppWidget(id, RemoteViews(views)) }
            }
        }

        private fun picture(c: Context, w: Int, h: Int, e: Estimate): Bitmap {
            // Two pictures travel to the home screen in one parcel: each is kept under about 600 KB
            val k = minOf(1f, kotlin.math.sqrt(150_000f / (w.coerceAtLeast(1) * h.coerceAtLeast(1))))
            val bw = (w * k).toInt().coerceAtLeast(120); val bh = (h * k).toInt().coerceAtLeast(80)
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            val d = c.resources.displayMetrics.density * k
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
            p.color = 0xEB0E1311.toInt()
            cv.drawRoundRect(r, 24 * d, 24 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0x33D9BB86
            r.inset(d / 2, d / 2); cv.drawRoundRect(r, 24 * d, 24 * d, p)
            val pad = 14 * d
            cv.save(); cv.translate(pad, pad)
            Gauge.draw(c, cv, bw - pad * 2, bh - pad * 2, e, Kit.Fixed.IVORY, 0xFF96A098.toInt(), widget = true)
            cv.restore()
            return b
        }
    }
}
