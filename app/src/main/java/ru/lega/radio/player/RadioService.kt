package ru.lega.radio.player

import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.lega.radio.R
import ru.lega.radio.data.StationStore

/**
 * Сервис воспроизведения.
 *
 * Задачи:
 *  - держать ExoPlayer вне Activity (играет при выключенном экране);
 *  - отдавать MediaSession (уведомление, кнопки гарнитуры, Bluetooth);
 *  - при ошибке потока переключаться на резервный адрес станции;
 *  - держать таймер сна (он должен срабатывать в фоне).
 */
@UnstableApi
class RadioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sleepTimer: SleepTimer
    private lateinit var shakeDetector: ShakeDetector
    private val stationStore by lazy { StationStore(this) }

    private var shakeEnabled = true
    private var maracasSoundId = 0
    private val soundPool: SoundPool by lazy {
        SoundPool.Builder().setMaxStreams(1).build()
    }

    /** Индекс потока, который играет сейчас (для переключения на резервный). */
    private var streamIndex = 0
    private var currentMediaId: String? = null

    override fun onCreate() {
        super.onCreate()

        sleepTimer = SleepTimer(serviceScope) {
            Log.i(TAG, "Таймер сна сработал — ставлю паузу")
            player.volume = 1f
            player.pause()
            updateShakeRegistration()
        }

        shakeDetector = ShakeDetector(this) { onShake() }

        maracasSoundId = soundPool.load(this, R.raw.maracas, 1)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    currentMediaId = mediaItem?.mediaId
                    streamIndex = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Ошибка потока (${error.errorCodeName}), пробую резервный", error)
                fallbackToNextStream()
            }
        })

        // Плавное затухание в последнюю минуту: громкость = остаток / 60 с.
        serviceScope.launch {
            SleepTimerState.remainingMs.collect { remaining ->
                val volume = when {
                    remaining == null -> 1f
                    remaining <= 0L -> 0f
                    remaining < SleepTimer.FADE_MS ->
                        remaining.toFloat() / SleepTimer.FADE_MS

                    else -> 1f
                }
                player.volume = volume
                updateShakeRegistration()
            }
        }

        // Настройка «продлевать таймер встряхиванием» может меняться на лету.
        serviceScope.launch {
            stationStore.shakeToExtend.collect { enabled ->
                shakeEnabled = enabled
                updateShakeRegistration()
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
    }

    /**
     * Канал управления из UI: сейчас это только таймер сна.
     * Команда проходит через MediaSession, поэтому её же можно
     * повесить на кнопку в уведомлении или виджете.
     */
    private val sessionCallback = object : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(SleepTimer.ACTION, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != SleepTimer.ACTION) {
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
                )
            }

            val minutes = args.getInt(SleepTimer.EXTRA_MINUTES, 0)
            if (minutes <= 0) {
                Log.i(TAG, "Таймер сна выключен")
                sleepTimer.cancel()
                player.volume = 1f
            } else {
                Log.i(TAG, "Таймер сна: $minutes мин")
                sleepTimer.start(minutes)
            }

            updateShakeRegistration()

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Переключение на следующий поток той же станции.
     * Если потоки кончились — оставляем ошибку игроку, UI покажет состояние.
     */
    private fun fallbackToNextStream() {
        val station = currentMediaId?.let(PlaybackRegistry::stationOf) ?: return
        val next = streamIndex + 1
        if (next >= station.streams.size) {
            Log.w(TAG, "Резервных потоков больше нет: ${station.name}")
            return
        }

        streamIndex = next
        val stream = station.streams[next]
        Log.i(TAG, "Переключаюсь на поток #$next: ${stream.url}")

        player.setMediaItem(PlaybackItems.build(station, stream))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Встряхивание: вернуть громкость, перезапустить таймер на тот же период
     * и «стукнуть» маракасами, чтобы было понятно, что сброс сработал.
     */
    private fun onShake() {
        if (!shakeEnabled) return
        if (!sleepTimer.restart()) return

        player.volume = 1f

        if (player.isPlaying) {
            soundPool.play(maracasSoundId, 1f, 1f, 1, 0, 1f)
        }

        Log.i(TAG, "Таймер сна продлён встряхиванием: ${sleepTimer.activeMinutes} мин")
    }

    /**
     * Датчик встряхивания нужен только в последнюю минуту — когда идёт
     * затухание и есть смысл «продлить». Всё остальное время он выключен,
     * чтобы не жечь батарею и не ловить случайные движения.
     */
    private fun updateShakeRegistration() {
        val remaining = SleepTimerState.remainingMs.value
        val shouldListen = shakeEnabled &&
            shakeDetector.supported &&
            remaining != null &&
            remaining > 0L &&
            remaining <= SleepTimer.FADE_MS

        if (shouldListen) shakeDetector.start() else shakeDetector.stop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Если пользователь смахнул приложение — останавливаем и играть не продолжаем.
        player.pause()
        stopSelf()
    }

    override fun onDestroy() {
        shakeDetector.stop()
        soundPool.release()
        sleepTimer.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RadioService"
        const val USER_AGENT = "LegaRadio/0.1"
    }
}
