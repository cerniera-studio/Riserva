package com.cerniera.riserva

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Screen
import com.cerniera.kit.Segmented
import com.cerniera.kit.Sheet
import com.cerniera.kit.Toggle
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.text

/**
 * Riserva (16 Nov 2026): a fuel gauge for the Fold's two screens. Hours left on the main screen
 * and on the cover, at this phone's own rates, learned from Fold Taskbar's meter; today's charge
 * drawn screen by screen; and a reserve lamp that says when to stay folded.
 */
class MainActivity : Activity() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var gauge: GaugeView? = null
    private val tick = object : Runnable {
        // Each minute the figures follow the charge; nothing else moves, so nothing scrolls away
        override fun run() { gauge?.estimate = Model.estimate(this@MainActivity, Sample.now(this@MainActivity, seat())); main.postDelayed(this, 60_000) }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Riserva")) return
        Kit.follow(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        // A reading of its own on opening, so the figures start from this minute
        val now = Sample.now(this, seat())
        if (now.level in 0..100) { Store.add(this, now); Reading.refresh(this, now) }
        render()
        main.removeCallbacks(tick); main.postDelayed(tick, 60_000)
    }

    override fun onPause() { super.onPause(); main.removeCallbacks(tick) }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); Kit.follow(this); render() }

    private fun seat() = if (resources.configuration.smallestScreenWidthDp >= 600) Seat.MAIN else Seat.COVER

    private fun render() {
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        val est = Model.estimate(this, Sample.now(this, seat()))
        val screen = Screen(this, "Riserva", "Galaxy Z Fold 8 Ultra")
        val panes = Panes(this, screen)

        // ---- The gauge: first in every shape
        val gauge = GaugeView(this).apply { estimate = est }
        this.gauge = gauge
        val power = primary(if (est.reserve) "Power saving  ›" else "Power saving", filled = est.reserve) {
            runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
        }
        if (panes.wide) {
            panes.lead.addView(gauge, LinearLayout.LayoutParams(-1, 0, 1f))
            panes.lead.addView(power, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        } else {
            screen.add(gauge.apply { layoutParams = LinearLayout.LayoutParams(-1, px(300)) }, px(6))
            screen.add(power, px(14))
        }

        // ---- The meter: only says something when readings are not arriving (the Giulietta principle)
        val taskbar = runCatching { packageManager.getPackageInfo(TASKBAR, 0) }.isSuccess
        val last = est.lastReading
        val stale = last == null || System.currentTimeMillis() - last > 3 * 3600_000L
        if (!taskbar || stale) {
            screen.section("Readings")
            screen.add(Card(this).apply {
                row(Row(this@MainActivity, if (!taskbar) "Fold Taskbar is needed" else "No readings from Fold Taskbar",
                    if (!taskbar) "Riserva learns from Fold Taskbar, which is always running and can see which screen is on. Install it and keep it switched on."
                    else "Update Fold Taskbar, keep it switched on, and check Read the battery for Riserva in its settings. Until readings arrive, Riserva only learns while it is open.",
                    if (taskbar) endValue("Open  ›", Kit.CHAMPAGNE) else null) {
                    packageManager.getLaunchIntentForPackage(TASKBAR)?.let { startActivity(it) }
                })
            })
        }

        // ---- Today
        screen.section("Today")
        screen.add(Card(this).apply {
            setPadding(px(14), px(14), px(14), px(10))
            addView(TimelineView(this@MainActivity).apply { samples = Model.today(this@MainActivity) })
            addView(legend())
        })

        // ---- What each screen costs
        screen.section("What each screen costs")
        screen.add(Card(this).apply {
            fun rate(seat: Seat, r: Rate?, learned: Float) = row(Row(this@MainActivity, seat.label,
                if (r == null) "Still learning: ${hm(learned)} of use seen" else "Learned from ${hm(r.hours)} over the last week",
                endValue(r?.let { "%.1f%% an hour".format(it.perHour) } ?: "—", if (r == null) Kit.MUTED else Kit.INK)))
            rate(Seat.MAIN, est.main, est.learnedMain)
            rate(Seat.COVER, est.cover, est.learnedCover)
            rate(Seat.OFF, est.off, 0f)
        })
        val ratio = if (est.main != null && est.cover != null) est.main.perHour / est.cover.perHour else null
        if (ratio != null) screen.add(text("An hour unfolded costs about %.1f hours on the cover.".format(ratio), 13.5f, Kit.MUTED).apply { setPadding(px(4), px(10), px(4), 0) })

        // ---- The reserve lamp
        screen.section("Reserve lamp")
        val choices = Prefs.RESERVE_CHOICES
        screen.add(text("Lights when the main screen has less than this left", 14f, Kit.MUTED, 450).apply { setPadding(px(4), 0, 0, px(8)) })
        screen.add(Segmented(this, choices.map { "${it.toInt()} h" }, choices.indexOf(Prefs.reserveHours(this)).coerceAtLeast(0)) {
            Prefs.setReserveHours(this, choices[it]); render()
        })
        screen.add(Card(this).apply {
            row(Row(this@MainActivity, "Tell me when it lights", "A quiet notification as the lamp lights, withdrawn when it goes out. Fold Taskbar's Spia shows it as a champagne lamp",
                Toggle(this@MainActivity, Prefs.tell(this@MainActivity)) { on ->
                    Prefs.setTell(this@MainActivity, on)
                    if (on && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }))
            row(Row(this@MainActivity, "Forget the readings", "Start learning again from now", endValue("Forget  ›", Kit.CHAMPAGNE)) { forget() })
        }, px(14))

        screen.add(doctrineCard(NOTES), px(24))
        screen.add(text("Version " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?"), 12f, Kit.MUTED).apply { setPadding(px(4), px(16), 0, px(24)) })

        val root = panes.build()
        setContentView(root)
        panes.record(root, mapOf("gauge" to gauge, "power" to power))
    }

    private fun legend() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(4), px(8), 0, 0)
        listOf("Main" to Kit.CHAMPAGNE, "Cover" to Kit.INK, "Off" to Kit.MUTED, "Charging" to Kit.GOOD).forEach { (name, colour) ->
            addView(View(this@MainActivity).apply { background = com.cerniera.kit.rounded(colour, px(2).toFloat()) }, LinearLayout.LayoutParams(px(12), px(3)).apply { gravity = android.view.Gravity.CENTER_VERTICAL; marginEnd = px(6) })
            addView(text(name, 12f, Kit.MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14) })
        }
    }

    private fun forget() {
        Sheet.show(this, "Forget the readings", action = "Forget" to { sheet ->
            Store.forget(this); Prefs.setLit(this, false); sheet.dismiss(); render()
        }) { sheet ->
            sheet.body.addView(text("Riserva keeps two weeks of readings on this phone and learns each screen's rate from the last seven days. Forgetting them starts again from now; the figures return after about twenty minutes of use on each screen.", 14f, Kit.MUTED))
        }
    }

    companion object {
        const val TASKBAR = "com.fergusonconsultancy.foldtaskbar"
        val NOTES = listOf(
            "Membrane" to "One tank, two readings. The main screen's hours and the cover's are the same charge read at two rates, so spending either lowers both, as a trip computer's range falls whichever road you take.",
            "Concentrato" to "Nothing but the two figures and the lamp at rest. Rates, today's line and the settings wait beside or below the gauge.",
            "The Giulietta principle" to "The reserve lamp is dark most days. The readings card appears only when readings stop arriving.",
            "How it learns" to "Fold Taskbar is always running, so it notes which screen is on, the charge and the current at every change and every five minutes, and hands each reading to Riserva. The charge lost between two readings belongs to the screen that was on. Charging is left out, and each day counts half as much as one three and a half days newer.",
        )
    }
}
