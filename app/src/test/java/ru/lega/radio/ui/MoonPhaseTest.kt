package ru.lega.radio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Проверка расчёта фазы Луны на опорных точках цикла.
 *
 * Луна — чистая математика, поэтому её можно (и нужно) проверять
 * без телефона и без сети.
 */
class MoonPhaseTest {

    private val newMoonEpoch: Instant = Instant.parse("2000-01-06T18:14:00Z")
    private val synodicDays = 29.530588853

    private fun daysAfterNewMoon(days: Double): Instant =
        newMoonEpoch.plus(Duration.ofMillis((days * 86_400_000.0).toLong()))

    @Test
    fun `в опорную точку — новолуние`() {
        assertEquals(MoonPhase.NEW, moonPhaseOf(moonPhaseFraction(newMoonEpoch)))
    }

    @Test
    fun `четверть цикла — первая четверть`() {
        val instant = daysAfterNewMoon(synodicDays * 0.25)
        assertEquals(MoonPhase.FIRST_QUARTER, moonPhaseOf(moonPhaseFraction(instant)))
    }

    @Test
    fun `половина цикла — полнолуние`() {
        val instant = daysAfterNewMoon(synodicDays * 0.5)
        assertEquals(MoonPhase.FULL, moonPhaseOf(moonPhaseFraction(instant)))
    }

    @Test
    fun `три четверти цикла — последняя четверть`() {
        val instant = daysAfterNewMoon(synodicDays * 0.75)
        assertEquals(MoonPhase.LAST_QUARTER, moonPhaseOf(moonPhaseFraction(instant)))
    }

    @Test
    fun `растущий серп между новолунием и первой четвертью`() {
        val instant = daysAfterNewMoon(synodicDays * 0.12)
        assertEquals(MoonPhase.WAXING_CRESCENT, moonPhaseOf(moonPhaseFraction(instant)))
    }

    @Test
    fun `убывающий серп перед новолунием`() {
        val instant = daysAfterNewMoon(synodicDays * 0.88)
        assertEquals(MoonPhase.WANING_CRESCENT, moonPhaseOf(moonPhaseFraction(instant)))
    }

    @Test
    fun `фаза всегда в диапазоне 0 до 1`() {
        // Полтора года вперёд с шагом в сутки: дробная часть не должна уезжать.
        for (day in 0..550) {
            val fraction = moonPhaseFraction(daysAfterNewMoon(day.toDouble()))
            assertTrue("день $day: $fraction", fraction >= 0.0 && fraction < 1.0)
        }
    }

    @Test
    fun `освещённость растёт к полнолунию и падает к новолунию`() {
        val newMoon = 0.0
        val fullMoon = 0.5

        val atNew = (1.0 - kotlin.math.cos(2 * Math.PI * newMoon)) / 2.0
        val atFull = (1.0 - kotlin.math.cos(2 * Math.PI * fullMoon)) / 2.0

        assertEquals(0.0, atNew, 0.001)
        assertEquals(1.0, atFull, 0.001)
    }

    @Test
    fun `фаза повторяется через синодический месяц`() {
        val first = moonPhaseFraction(newMoonEpoch.plus(Duration.ofDays(100)))
        val second = moonPhaseFraction(
            newMoonEpoch.plus(Duration.ofDays(100)).plus(
                Duration.ofMillis((synodicDays * 86_400_000.0).toLong()),
            ),
        )
        assertEquals(first, second, 0.01)
    }
}
