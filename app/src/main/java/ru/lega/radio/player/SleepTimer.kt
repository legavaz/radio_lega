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

    /** [minutes] <= 0 — выключить таймер. */
    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return

        job = scope.launch {
            var leftMs = minutes * 60_000L
            SleepTimerState.update(leftMs)

            while (leftMs > 0) {
                delay(1_000)
                leftMs -= 1_000
                SleepTimerState.update(leftMs.coerceAtLeast(0))
            }

            SleepTimerState.update(null)
            onFinish()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        SleepTimerState.update(null)
    }

    companion object {
        /** Действие команды MediaSession «поставить/снять таймер сна». */
        const val ACTION = "ru.lega.radio.SLEEP_TIMER"

        /** Сколько минут передаётся в [Bundle] команды. */
        const val EXTRA_MINUTES = "minutes"
    }
}
