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
import com.cerniera.kit.Backdrop
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
 * The widget (16 Nov 2026; drawn to the pixel 17 Nov). The gauge drawn at the widget's own size, one
 * picture for each way round, so turning the phone shows a picture made for that shape.
 *
 * Crispness, as in Fold Widgets: every picture is drawn at the density of the screen in use now (on a
 * Fold the app's resources can carry the other screen's density), at full resolution, never shrunk to
 * save memory. Once a tap on the widget has measured its real size (the tap's source bounds), the
 * picture for that screen and orientation is drawn to exactly those pixels, so the launcher shows it
 * 1:1 with no resampling. Until then the launcher's reported size is used.
 */
class GaugeWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = updateAll(c, null)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = updateAll(c, null)

    companion object {
        const val EXTRA_ID = "widget_id"
        fun store(c: Context) = c.getSharedPreferences("riserva_widgets", Context.MODE_PRIVATE)

        /** One size per orientation, portrait first, in dp: what the launcher says it gives the widget. */
        private fun sizes(o: Bundle): List<SizeF> {
            @Suppress("DEPRECATION")
            val listed = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }.orEmpty()
            if (listed.isNotEmpty()) {
                val p = listed.minBy { it.width / it.height }; val l = listed.maxBy { it.width / it.height }
                return if (same(p, l)) listOf(p) else listOf(p, l)
            }
            val pw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180); val ph = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            val lw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, pw); val lh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, ph)
            val p = SizeF(pw.toFloat(), ph.toFloat()); val l = SizeF(lw.toFloat(), lh.toFloat())
            return if (same(p, l)) listOf(p) else listOf(p, l)
        }

        private fun same(a: SizeF, b: SizeF) = Math.abs(a.width - b.width) < 1.5f && Math.abs(a.height - b.height) < 1.5f

        fun updateAll(c: Context, given: Estimate?) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, GaugeWidget::class.java))
            if (ids.isEmpty()) return
            val e = given ?: Model.estimate(c, Sample.now(c, Store.last(c)?.seat ?: Seat.COVER))
            val d = Backdrop.density(c)
            val screen = Backdrop.screenSize(c)
            val portrait = screen.y >= screen.x
            // The family look (26 Nov 2026): where live mode last saw these widgets, then Fold Widgets' look, once for all
            com.cerniera.kit.Backdrop.invalidate()
            com.cerniera.kit.Family.seats(c, store(c), ids) { sizes(m.getAppWidgetOptions(it)) }
            val look = look(c)
            ids.forEach { id ->
              // Sent through the Kit's WidgetBudget (27 Nov 2026): within Android's real cap, and a refusal
              // is redrawn smaller rather than leaving the gauge on its last picture
              com.cerniera.kit.WidgetBudget.send(c, m, id) {
                val budget = com.cerniera.kit.WidgetBudget.bytes(c)
                val shrink = Math.sqrt(com.cerniera.kit.WidgetBudget.scale(c).toDouble()).toFloat()
                val list = sizes(m.getAppWidgetOptions(id))
                val now = if (portrait) list.first() else list.last()
                val seat = Backdrop.seat(store(c), id)
                val map = LinkedHashMap<SizeF, RemoteViews>()
                var spent = 0L
                // The size in use first, so it is never the one that gives way
                // Plus the sizes' shared part, for a launcher stack whose real box none of them fits (27 Nov 2026)
                com.cerniera.kit.WidgetSizes.withShared(listOf(now) + list.filter { !same(it, now) }).forEach { (s, up) ->
                    val isNow = up == 1f && same(s, now)
                    // Measured by a tap at this very size, on this screen: its true pixels
                    var px = Math.round(s.width * d * up); var py = Math.round(s.height * d * up)
                    if (isNow && seat != null && seat.exact && seat.screenW == screen.x && seat.screenH == screen.y &&
                        Math.abs(seat.repW - s.width) < 1.5f && Math.abs(seat.repH - s.height) < 1.5f) {
                        px = Math.round(seat.width); py = Math.round(seat.height)
                    }
                    val want = px.toLong() * py * 4
                    val k = shrink * (if (!isNow && spent + want > budget) Math.sqrt((budget - spent).coerceAtLeast(budget / 4).toDouble() / want).toFloat() else 1f)
                    val bw = Math.round(px * k).coerceAtLeast(60); val bh = Math.round(py * k).coerceAtLeast(40)
                    spent += bw.toLong() * bh * 4
                    map[s] = RemoteViews(c.packageName, R.layout.gauge).apply {
                        setImageViewBitmap(R.id.picture, picture(c, id, bw, bh, d * up * k, e, look))
                        setContentDescription(R.id.picture, "Riserva: main screen ${hm(e.hoursMain)}, cover ${hm(e.hoursCover)}")
                        // Each widget's own tap, carrying its id: the tap's bounds measure the widget
                        setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, id,
                            Intent(c, MainActivity::class.java).putExtra(EXTRA_ID, id).putExtra("rw", s.width).putExtra("rh", s.height),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
                if (map.size == 1) map.values.first() else RemoteViews(map)
              }
            }
        }

        /** The tap that opened Riserva from a widget: records the widget's true size, and redraws if it changed. */
        fun measured(c: Context, i: Intent) {
            val id = i.getIntExtra(EXTRA_ID, -1).takeIf { it >= 0 } ?: return
            val reported = SizeF(i.getFloatExtra("rw", 0f), i.getFloatExtra("rh", 0f))
            if (Backdrop.record(c, store(c), i, id, null, null, true, reported)) updateAll(c, null)
        }

        /**
         * The picture, [bw] x [bh] px at density [d] (22 Nov 2026). It had its own fixed dark card;
         * it now wears Fold Widgets' finish and corners (read from its LookProvider, as Nodo and Dorso
         * do), over the wallpaper behind it, and is composed from the family's widget grammar.
         */
        private fun picture(c: Context, id: Int, bw: Int, bh: Int, d: Float, e: Estimate, look: Pair<com.cerniera.kit.Finish, Float>): Bitmap {
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b).apply { scale(d, d) }
            val w = bw / d; val h = bh / d
            val s = com.cerniera.kit.Surface(look.first, com.cerniera.kit.Family.ground(c, store(c), id), w, h, minOf(look.second, minOf(w, h) / 2), 1.1f / d)
            s.paint(cv)
            Gauge.widget(c, cv, s, e)
            return b
        }

        /**
         * Fold Widgets' finish and corners, and the picture behind its glass, through the Kit's Family
         * (26 Nov 2026), the one code every Cerniera widget uses. A slow answer keeps the last look.
         * Since 26 Nov the gauge also takes its place from Fold Widgets' live mode (its picture's view
         * is now named root, as live mode looks for), so its glass reads the same slice of wallpaper
         * as the widgets beside it; before, it wore the wallpaper's overall colour and an adaptive
         * finish took a different tone over it.
         */
        fun look(c: Context): Pair<com.cerniera.kit.Finish, Float> =
            com.cerniera.kit.Family.look(c, store(c)).let { it.finish to it.radius.toFloat() }

        /** The family's preview canvas (960 px at 2.625 px per dp), Carbonio, for the picker. */
        fun preview(c: Context, e: Estimate): Bitmap {
            val d = 2.625f; val w = 255f; val h = 120f
            val b = Bitmap.createBitmap(Math.round(w * d), Math.round(h * d), Bitmap.Config.ARGB_8888)
            val cv = Canvas(b).apply { scale(d, d) }
            val s = com.cerniera.kit.Surface(com.cerniera.kit.Finish.CARBONIO, com.cerniera.kit.Ground.flat(0xFF16191A.toInt()), w, h, 24f, 1.1f / d)
            s.paint(cv); Gauge.widget(c, cv, s, e)
            val out = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(b, ((960 - b.width) / 2).toFloat(), ((960 - b.height) / 2).toFloat(), null)
            return out
        }
    }
}

/** Fold Widgets' look or the wallpaper changed (22 Nov 2026): redraw the gauge to match. */
class GaugeLook : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == "com.cerniera.LOOK_CHANGED") { com.cerniera.kit.Backdrop.invalidate(); GaugeWidget.updateAll(c, null) }
    }
}
