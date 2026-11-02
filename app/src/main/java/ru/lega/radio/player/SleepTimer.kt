package ru.lega.radio.player

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояние таймера сна.
 *
 * Живёт отдельно от сервиса, потому что UI (Compose) и плеер находятся
 * в одном процессе — так экран может читать обратный отсчёт, не поднимая
 * лишний канал связи с MediaSession.
 */
object SleepTimerState {
    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Сколько осталось до остановки; null — таймер выключен. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    internal fun update(value: Long?) {
        _remainingMs.value = value
    }
}

/**
 * Таймер сна.
 *
 * Обязан жить в сервисе, а не в ViewModel: экран может быть выключен или
 * приложение свёрнуто, а музыка должна остановиться в назначенное время.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val onFinish: () -> Unit,
) {
    private var job: Job? = null

    /** Период, на который запущен таймер (минуты). 0 — таймер не идёт. */
    var activeMinutes: Int = 0
        private set

    /** Идёт ли таймер сейчас. */
    val isActive: Boolean
        get() = job != null && activeMinutes > 0

    /** [minutes] <= 0 — выключить таймер. */
    fun start(minutes: Int) {
        stopJob()
        if (minutes <= 0) {
            activeMinutes = 0
            SleepTimerState.update(null)
            return
        }

        activeMinutes = minutes
        job = scope.launch {
            var leftMs = minutes * 60_000L
            SleepTimerState.update(leftMs)

            while (leftMs > 0) {
                delay(1_000)
                leftMs -= 1_000
                SleepTimerState.update(leftMs.coerceAtLeast(0))
            }

            activeMinutes = 0
            job = null
            SleepTimerState.update(null)
            onFinish()
        }
    }

    /**
     * Перезапуск на тот же период — используется при встряхивании.
     * Возвращает false, если таймер не шёл.
     */
    fun restart(): Boolean {
        val minutes = activeMinutes
        if (minutes <= 0) return false
        start(minutes)
        return true
    }

    fun cancel() {
        stopJob()
        activeMinutes = 0
        SleepTimerState.update(null)
    }

    private fun stopJob() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Действие команды MediaSession «поставить/снять таймер сна». */
        const val ACTION = "ru.lega.radio.SLEEP_TIMER"

        /** Сколько минут передаётся в [Bundle] команды. */
        const val EXTRA_MINUTES = "minutes"

        /** За сколько до конца начинается плавное затухание. */
        const val FADE_MS = 60_000L
    }
}
