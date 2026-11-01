package ru.lega.radio.ui

import java.time.Duration
import java.time.Instant

/** Восемь фаз Луны — для подписи и доступности. */
enum class MoonPhase(val title: String) {
    NEW("новолуние"),
    WAXING_CRESCENT("растущий серп"),
    FIRST_QUARTER("первая четверть"),
    WAXING_GIBBOUS("растущая Луна"),
    FULL("полнолуние"),
    WANING_GIBBOUS("убывающая Луна"),
    LAST_QUARTER("последняя четверть"),
    WANING_CRESCENT("убывающий серп"),
}

/**
 * Положение в синодическом цикле: 0 — новолуние, 0.5 — полнолуние.
 *
 * Считается от опорного новолуния 2000-01-06 18:14 UTC. Точность порядка
 * половины суток — для значка этого с запасом, интернет и разрешения не нужны.
 *
 * Про координаты: доля освещённого диска одинакова на всей Земле в один и
 * тот же момент. От места зависит только ориентация серпа (в южном полушарии
 * она перевёрнута) и время восхода/захода Луны.
 */
fun moonPhaseFraction(instant: Instant = Instant.now()): Double {
    val days = Duration.between(NEW_MOON_EPOCH, instant).toMillis() / 86_400_000.0
    val cycles = days / SYNODIC_MONTH_DAYS
    return ((cycles % 1.0) + 1.0) % 1.0
}

/** Фаза по положению в цикле — для подписи. */
fun moonPhaseOf(fraction: Double): MoonPhase {
    val index = ((fraction * 8.0) + 0.5).toInt().mod(MoonPhase.entries.size)
    return MoonPhase.entries[index]
}

/** Опорное новолуние: 2000-01-06 18:14 UTC. */
private val NEW_MOON_EPOCH: Instant = Instant.parse("2000-01-06T18:14:00Z")

/** Средняя длительность синодического месяца в сутках. */
private const val SYNODIC_MONTH_DAYS = 29.530588853
