package com.cerniera.riserva

import android.content.Context
import android.os.BatteryManager
import java.io.File
import kotlin.math.pow

/** Which screen was on: the main screen, the cover screen, or neither. */
enum class Seat(val key: String, val label: String) {
    MAIN("main", "Main screen"), COVER("cover", "Cover screen"), OFF("off", "Screen off");
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

/**
 * One reading of the meter: when, which screen was on, the charge as a percentage, the charge
 * counter in µAh (0 where the phone doesn't report it), the current in µA, and whether charging.
 */
data class Sample(val t: Long, val seat: Seat, val level: Int, val charge: Int, val current: Int, val charging: Boolean) {
    fun line() = "$t,${seat.key},$level,$charge,$current,${if (charging) 1 else 0}"
    companion object {
        fun parse(s: String): Sample? = s.split(',').takeIf { it.size >= 6 }?.let { p ->
            runCatching { Sample(p[0].toLong(), Seat.of(p[1])!!, p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5] == "1") }.getOrNull()
        }

        /** A reading taken by Riserva itself, while it is open on [seat]. */
        fun now(c: Context, seat: Seat): Sample {
            val bm = c.getSystemService(BatteryManager::class.java)
            return Sample(System.currentTimeMillis(), seat, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER), bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), bm.isCharging)
        }
    }
}

/**
 * The readings, one line each in a file of Riserva's own. Kept for [KEEP_DAYS] days; nothing
 * leaves the phone. (Excluded from nothing: it is only numbers, and a restored history helps.)
 */
object Store {
    private const val KEEP_DAYS = 14
    private fun file(c: Context) = File(c.filesDir, "readings.csv")

    @Synchronized fun add(c: Context, s: Sample) {
        val f = file(c)
        f.appendText(s.line() + "\n")
        // Trimmed now and then: when the file has grown past roughly two weeks of readings
        if (f.length() > 600_000) {
            val cut = System.currentTimeMillis() - KEEP_DAYS * DAY
            f.writeText(all(c).filter { it.t >= cut }.joinToString("") { it.line() + "\n" })
        }
    }

    @Synchronized fun all(c: Context): List<Sample> =
        runCatching { file(c).readLines().mapNotNull(Sample::parse).sortedBy { it.t } }.getOrDefault(emptyList())

    fun last(c: Context): Sample? = all(c).lastOrNull()

    @Synchronized fun forget(c: Context) { file(c).delete() }

    const val DAY = 24 * 3600_000L
}

/** A learned rate: percentage points an hour, and how many hours of use it was learned from. */
data class Rate(val perHour: Float, val hours: Float)

/** Everything the gauge shows. Hours are null while a rate is still being learned. */
data class Estimate(
    val level: Float,
    val charging: Boolean,
    val main: Rate?,
    val cover: Rate?,
    val off: Rate?,
    val learnedMain: Float,
    val learnedCover: Float,
    val reserveAt: Float,
    val lastReading: Long?,
) {
    val hoursMain get() = main?.let { level / it.perHour }
    val hoursCover get() = cover?.let { level / it.perHour }
    /** The reserve lamp: on battery, with less than the chosen hours left on the main screen. */
    val reserve get() = !charging && (hoursMain ?: Float.MAX_VALUE) < reserveAt
}

/**
 * Turns readings into a drain rate for each screen, the way a trip computer turns litres into miles.
 * Between two readings the phone was in the first reading's state, so the charge lost between them
 * belongs to that state. Charging is left out. Recent days count most: a reading's weight halves
 * every [HALF_LIFE_DAYS], so a change in how the phone is used shows within a few days.
 */
object Model {
    private const val HALF_LIFE_DAYS = 3.5
    /** Longest believable gap between readings with a screen on (readings come every five minutes). */
    private const val MAX_ON_GAP = 15 * 60_000L
    private const val MAX_OFF_GAP = 16 * 3600_000L
    /** How much use a rate needs before it is shown, in hours. */
    private const val LEARN_ON = 0.33f
    private const val LEARN_OFF = 2f

    /** The battery's full charge in µAh, worked out from the charge counter against the percentage. */
    fun capacity(samples: List<Sample>): Double? {
        val est = samples.filter { it.charge > 0 && it.level in 20..100 }.map { it.charge * 100.0 / it.level }.sorted()
        return if (est.size < 3) null else est[est.size / 2]
    }

    /** The charge as a percentage, finer than the 1% steps where the counter is reported. */
    fun pct(s: Sample, cap: Double?): Double = if (cap != null && s.charge > 0) s.charge * 100.0 / cap else s.level.toDouble()

    fun estimate(c: Context, now: Sample): Estimate {
        val all = Store.all(c)
        val cap = capacity(all)
        val since = now.t - 7 * Store.DAY
        val drop = DoubleArray(3); val hours = DoubleArray(3); val raw = DoubleArray(3)
        all.filter { it.t >= since }.zipWithNext().forEach { (a, b) ->
            val dt = b.t - a.t
            if (dt <= 0 || a.charging || b.charging) return@forEach
            if (dt > (if (a.seat == Seat.OFF) MAX_OFF_GAP else MAX_ON_GAP)) return@forEach
            val d = pct(a, cap) - pct(b, cap)
            if (d < -0.5) return@forEach  // charged in between, unseen
            val w = 0.5.pow((now.t - b.t) / (Store.DAY * HALF_LIFE_DAYS))
            val h = dt / 3600_000.0
            val i = a.seat.ordinal
            drop[i] += d.coerceAtLeast(0.0) * w; hours[i] += h * w; raw[i] += h
        }
        fun rate(s: Seat, need: Float): Rate? {
            val i = s.ordinal
            if (raw[i] < need || hours[i] <= 0 || drop[i] <= 0) return null
            return Rate((drop[i] / hours[i]).toFloat().coerceAtLeast(0.05f), raw[i].toFloat())
        }
        return Estimate(pct(now, cap).toFloat().coerceIn(0f, 100f), now.charging,
            rate(Seat.MAIN, LEARN_ON), rate(Seat.COVER, LEARN_ON), rate(Seat.OFF, LEARN_OFF),
            raw[Seat.MAIN.ordinal].toFloat(), raw[Seat.COVER.ordinal].toFloat(), Prefs.reserveHours(c),
            Prefs.lastMeter(c).takeIf { it > 0 })
    }

    /** Today's readings, for the timeline: midnight to now. */
    fun today(c: Context): List<Sample> {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return Store.all(c).filter { it.t >= cal.timeInMillis }
    }
}

/** Hours as "3 h 40", or "45 min" under an hour. */
fun hm(h: Float?): String {
    if (h == null || h.isNaN() || h.isInfinite()) return "—"
    val m = (h * 60).toInt()
    return if (m < 60) "$m min" else "${m / 60} h ${"%02d".format(m % 60)}"
}

object Prefs {
    private fun of(c: Context) = c.getSharedPreferences("riserva", Context.MODE_PRIVATE)
    val RESERVE_CHOICES = listOf(1f, 2f, 3f)
    fun reserveHours(c: Context) = of(c).getFloat("reserve_hours", 2f)
    fun setReserveHours(c: Context, v: Float) = of(c).edit().putFloat("reserve_hours", v).apply()
    fun tell(c: Context) = of(c).getBoolean("tell", true)
    fun setTell(c: Context, v: Boolean) = of(c).edit().putBoolean("tell", v).apply()
    /** Whether the reserve lamp was lit at the last reading, so a notice goes only when it lights. */
    fun lit(c: Context) = of(c).getBoolean("lit", false)
    fun setLit(c: Context, v: Boolean) = of(c).edit().putBoolean("lit", v).apply()
    /** When Fold Taskbar's meter last sent a reading. */
    fun lastMeter(c: Context) = of(c).getLong("last_meter", 0L)
    fun setLastMeter(c: Context, v: Long) = of(c).edit().putLong("last_meter", v).apply()
}
