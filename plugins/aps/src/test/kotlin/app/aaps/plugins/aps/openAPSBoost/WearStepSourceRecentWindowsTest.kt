package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.SC
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests [WearStepSource.recentWindows] — the watch-first / phone-fallback resolver for the Boost
 * engine's recent step windows. Pure function, no Android/DI needed.
 */
class WearStepSourceRecentWindowsTest {

    private val now = 1_000_000_000L
    private val phone = "Pixel 8 sensor"     // MetricsSampler phoneSensorDevice convention ("<mfr> <model> sensor")
    private val watch = "Samsung SM-R860"    // AAPS Wear / worn-source device tag

    private fun sc(ts: Long, duration: Long, device: String, s5: Int = 0, s15: Int = 0, s30: Int = 0, s60: Int = 0) =
        SC(
            duration = duration, timestamp = ts, device = device,
            steps5min = s5, steps10min = 0, steps15min = s15, steps30min = s30, steps60min = s60, steps180min = 0
        )

    @Test fun `no rows returns null so caller falls back to the live pedometer`() {
        assertThat(WearStepSource.recentWindows(emptyList(), now)).isNull()
    }

    @Test fun `phone-sampler rows are ignored so caller falls back to the live pedometer`() {
        val rows = listOf(sc(now, 3_600_000, phone, s5 = 10, s15 = 30, s30 = 60, s60 = 120))
        assertThat(WearStepSource.recentWindows(rows, now)).isNull()
    }

    @Test fun `a fresh worn row yields its windows`() {
        val rows = listOf(sc(now, 3_600_000, watch, s5 = 7, s15 = 20, s30 = 35, s60 = 60))
        val w = WearStepSource.recentWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(7)
        assertThat(w.m15).isEqualTo(20)
        assertThat(w.m30).isEqualTo(35)
        assertThat(w.m60).isEqualTo(60)
    }

    @Test fun `a stale worn row is not fresh, returns null`() {
        val stale = now - (WearStepSource.FRESH_MS + 1)
        val rows = listOf(sc(stale, 3_600_000, watch, s5 = 7, s60 = 60))
        assertThat(WearStepSource.recentWindows(rows, now)).isNull()
    }

    @Test fun `max-aggregates the 6 progressively-filled rows at the newest timestamp`() {
        // Mirrors the wear feed: 6 rows per timestamp, only longer-duration rows carry wider windows.
        val rows = listOf(
            sc(now, 300_000, watch, s5 = 8),
            sc(now, 900_000, watch, s5 = 8, s15 = 22),
            sc(now, 1_800_000, watch, s5 = 8, s15 = 22, s30 = 40),
            sc(now, 3_600_000, watch, s5 = 8, s15 = 22, s30 = 40, s60 = 75)
        )
        val w = WearStepSource.recentWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(8)
        assertThat(w.m15).isEqualTo(22)
        assertThat(w.m30).isEqualTo(40)
        assertThat(w.m60).isEqualTo(75)
    }

    @Test fun `a mid-insert partial timestamp still yields a non-null worn result`() {
        // Only the short-duration row landed yet; wider windows read 0 until the rest are inserted.
        val rows = listOf(sc(now, 300_000, watch, s5 = 8))
        val w = WearStepSource.recentWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(8)
        assertThat(w.m60).isEqualTo(0)
    }

    @Test fun `newest fresh worn timestamp wins over an older fresh one`() {
        val older = now - 120_000
        val rows = listOf(
            sc(older, 3_600_000, watch, s5 = 1, s60 = 10),
            sc(now, 3_600_000, watch, s5 = 5, s60 = 50)
        )
        val w = WearStepSource.recentWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(5)
        assertThat(w.m60).isEqualTo(50)
    }

    @Test fun `worn row wins even when a phone-sampler row shares the newest timestamp`() {
        val rows = listOf(
            sc(now, 3_600_000, phone, s5 = 99, s60 = 999),
            sc(now, 3_600_000, watch, s5 = 5, s60 = 50)
        )
        val w = WearStepSource.recentWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(5)
        assertThat(w.m60).isEqualTo(50)
    }

    // ── phoneWindows: the "Phone steps only" path — reads the phone-sensor sampler rows the graph shows ──

    private fun scFull(
        ts: Long, duration: Long, device: String,
        s5: Int = 0, s10: Int = 0, s15: Int = 0, s30: Int = 0, s60: Int = 0, s180: Int = 0
    ) = SC(
        duration = duration, timestamp = ts, device = device,
        steps5min = s5, steps10min = s10, steps15min = s15, steps30min = s30, steps60min = s60, steps180min = s180
    )

    @Test fun `phoneWindows returns null when only a worn row is present`() {
        val rows = listOf(scFull(now, 3_600_000, watch, s5 = 5, s60 = 50))
        assertThat(WearStepSource.phoneWindows(rows, now)).isNull()
    }

    @Test fun `phoneWindows reads the phone-sampler row and ignores the worn row`() {
        val rows = listOf(
            scFull(now, 3_600_000, watch, s5 = 5, s60 = 50),
            scFull(now, 10_800_000, phone, s5 = 10, s10 = 18, s15 = 25, s30 = 40, s60 = 70, s180 = 120)
        )
        val w = WearStepSource.phoneWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(10)
        assertThat(w.m10).isEqualTo(18)
        assertThat(w.m15).isEqualTo(25)
        assertThat(w.m30).isEqualTo(40)
        assertThat(w.m60).isEqualTo(70)
        assertThat(w.m180).isEqualTo(120)
    }

    @Test fun `phoneWindows max-aggregates progressively-filled phone rows at the newest timestamp`() {
        val rows = listOf(
            scFull(now, 300_000, phone, s5 = 9),
            scFull(now, 600_000, phone, s5 = 9, s10 = 15),
            scFull(now, 10_800_000, phone, s5 = 9, s10 = 15, s15 = 20, s30 = 35, s60 = 60, s180 = 100)
        )
        val w = WearStepSource.phoneWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(9)
        assertThat(w.m10).isEqualTo(15)
        assertThat(w.m180).isEqualTo(100)
    }

    @Test fun `phoneWindows ignores a stale phone row`() {
        val stale = now - (WearStepSource.FRESH_MS + 1)
        val rows = listOf(scFull(stale, 10_800_000, phone, s5 = 10, s180 = 100))
        assertThat(WearStepSource.phoneWindows(rows, now)).isNull()
    }

    @Test fun `phoneWindows newest phone timestamp wins over an older fresh one`() {
        val older = now - 120_000
        val rows = listOf(
            scFull(older, 10_800_000, phone, s5 = 1, s180 = 10),
            scFull(now, 10_800_000, phone, s5 = 5, s180 = 50)
        )
        val w = WearStepSource.phoneWindows(rows, now)!!
        assertThat(w.m5).isEqualTo(5)
        assertThat(w.m180).isEqualTo(50)
    }

    @Test fun `stepsToday skips a glitching-counter bucket so the raw cumulative can't inflate the total`() {
        val dayStart = now - 3_600_000L
        val rows = listOf(
            scFull(dayStart + 300_000, 300_000, phone, s5 = 100),
            scFull(dayStart + 600_000, 300_000, phone, s5 = 254_746), // raw cumulative-since-boot leaked in
            scFull(dayStart + 900_000, 300_000, phone, s5 = 150)
        )
        // The 254 746 bucket is dropped; only the plausible buckets sum.
        assertThat(WearStepSource.stepsToday(rows, dayStart, now)).isEqualTo(250)
    }
}
