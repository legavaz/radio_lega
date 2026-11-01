package ru.lega.radio.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.lega.radio.data.RadioBrowserApi
import ru.lega.radio.data.StationStore
import ru.lega.radio.domain.Station
import ru.lega.radio.player.PlaybackItems
import ru.lega.radio.player.PlaybackRegistry
import ru.lega.radio.player.RadioService
import ru.lega.radio.player.SleepTimer
import ru.lega.radio.player.SleepTimerState

/**
 * Активный фильтр каталога. Одновременно выбран ровно один —
 * это не «источник + галочка», а взаимоисключающий набор, как вкладки.
 */
enum class CatalogFilter(val key: String, val countryCode: String?) {
    RUSSIA(key = "ru", countryCode = "RU"),
    ALL(key = "all", countryCode = null),
    FAVORITES(key = "favorites", countryCode = null),
}

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val api = RadioBrowserApi()
    private val store = StationStore(app)

    /** Каталог текущего источника (Россия или Все). */
    private val _stations = MutableStateFlow<List<Station>>(emptyList())

    /**
     * Все станции, которые мы когда-либо видели. Нужны вкладке «Избранное»:
     * избранное хранится как набор id, а показать нужно карточки станций.
     */
    private val _knownStations = MutableStateFlow<Map<String, Station>>(emptyMap())

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(CatalogFilter.RUSSIA)
    val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

    val favorites: StateFlow<Set<String>> = store.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val visibleStations: StateFlow<List<Station>> =
        combine(
            _filter,
            _stations,
            _knownStations,
            favorites,
            _query,
        ) { filter, stations, known, favs, q ->
            val base = when (filter) {
                CatalogFilter.FAVORITES -> favs
                    .asSequence()
                    .mapNotNull { known[it] }
                    .sortedBy { it.name.lowercase() }
                    .toList()

                else -> stations
            }

            val needle = q.trim()
            if (needle.isEmpty()) {
                base
            } else {
                base.filter {
                    it.name.contains(needle, ignoreCase = true) ||
                        it.genre?.contains(needle, ignoreCase = true) == true ||
                        it.country?.contains(needle, ignoreCase = true) == true
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- плеер ---
    val isPlaying = MutableStateFlow(false)
    val currentTitle = MutableStateFlow<String?>(null)
    val currentId = MutableStateFlow<String?>(null)
    val playbackError = MutableStateFlow<String?>(null)

    /** Текущая станция для мини-плеера (логотип, избранное). */
    val currentStation: StateFlow<Station?> =
        combine(currentId, _knownStations) { id, known ->
            id?.let { known[it] }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var refreshJob: Job? = null

    init {
        // Подтягиваем оба кэша, чтобы «Избранное» умело показывать станции
        // из любого источника, даже если сейчас открыт только один.
        CatalogFilter.entries.forEach { rememberStations(store.readCache(it.key)) }

        val cached = store.readCache(_filter.value.key)
        _stations.value = cached
        _loading.value = cached.isEmpty()

        refresh()
        connectToService()
    }

    private fun rememberStations(list: List<Station>) {
        if (list.isEmpty()) return
        _knownStations.value = _knownStations.value + list.associateBy { it.id }
    }

    private fun connectToService() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, RadioService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying.value = playing
                            // Запоминаем «играла ли», чтобы при следующем запуске
                            // восстановить станцию в том же состоянии.
                            currentId.value
                                ?.let { id -> _knownStations.value[id] }
                                ?.let { station ->
                                    viewModelScope.launch { store.saveLastStation(station, playing) }
                                }
                        }

                        override fun onMediaItemTransition(
                            mediaItem: androidx.media3.common.MediaItem?,
                            reason: Int,
                        ) {
                            currentId.value = mediaItem?.mediaId
                            currentTitle.value = mediaItem?.mediaMetadata?.title?.toString()
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) playbackError.value = null
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            playbackError.value = error.errorCodeName
                        }
                    })
                    currentId.value = mediaController.currentMediaItem?.mediaId
                    currentTitle.value = mediaController.currentMediaItem?.mediaMetadata?.title?.toString()
                    isPlaying.value = mediaController.isPlaying

                    restoreLastStation(mediaController)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun play(station: Station) {
        val mediaController = controller ?: return
        val stream = station.streams.firstOrNull() ?: return

        rememberStations(listOf(station))
        PlaybackRegistry.put(station)
        viewModelScope.launch { store.saveLastStation(station, wasPlaying = true) }
        playbackError.value = null
        mediaController.setMediaItem(PlaybackItems.build(station, stream))
        mediaController.prepare()
        mediaController.play()
    }

    /**
     * Возвращает станцию, на которой приложение закрыли.
     *
     * Если она играла — продолжаем воспроизведение; если стояла на паузе —
     * просто показываем её в мини-плеере, чтобы можно было нажать «плей».
     */
    private fun restoreLastStation(mediaController: MediaController) {
        if (mediaController.currentMediaItem != null) return

        viewModelScope.launch {
            val saved = store.readLastStation() ?: return@launch
            val station = saved.first
            val wasPlaying = saved.second
            val stream = station.streams.firstOrNull() ?: return@launch

            rememberStations(listOf(station))
            PlaybackRegistry.put(station)

            mediaController.setMediaItem(PlaybackItems.build(station, stream))
            currentId.value = station.id
            currentTitle.value = station.name

            if (wasPlaying) {
                mediaController.prepare()
                mediaController.play()
            }
        }
    }

    fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
    }

    fun stop() {
        controller?.stop()
    }

    /** Сколько осталось до остановки по таймеру сна; null — таймер выключен. */
    val sleepRemaining: StateFlow<Long?> = SleepTimerState.remainingMs

    /** [minutes] = 0 — выключить таймер сна. */
    fun setSleepTimer(minutes: Int) {
        val mediaController = controller ?: return
        val args = Bundle().apply { putInt(SleepTimer.EXTRA_MINUTES, minutes) }
        mediaController.sendCustomCommand(SessionCommand(SleepTimer.ACTION, Bundle.EMPTY), args)
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Переключение фильтра: выбран всегда ровно один. */
    fun setFilter(newFilter: CatalogFilter) {
        if (_filter.value == newFilter) return
        _filter.value = newFilter

        // «Избранное» живёт на локальных данных, в сеть не ходим.
        if (newFilter == CatalogFilter.FAVORITES) {
            refreshJob?.cancel()
            _loading.value = false
            _error.value = null
            return
        }

        val cached = store.readCache(newFilter.key)
        _stations.value = cached
        rememberStations(cached)
        _loading.value = cached.isEmpty()
        _error.value = null
        refresh()
    }

    fun toggleFavorite(station: Station) {
        rememberStations(listOf(station))
        viewModelScope.launch { store.toggleFavorite(station.id) }
    }

    fun refresh() {
        val filter = _filter.value
        if (filter == CatalogFilter.FAVORITES) return

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            var received = false

            runCatching {
                api.topStationsFlow(countryCode = filter.countryCode).collect { list ->
                    if (list.isNotEmpty()) {
                        received = true
                        rememberStations(list)
                        // Пока пользователь не переключил фильтр, обновляем список
                        if (_filter.value == filter) {
                            _stations.value = list
                            store.writeCache(filter.key, list)
                        }
                        _loading.value = false
                    }
                }
            }.onFailure { throwable ->
                Log.w("RadioViewModel", "Не удалось обновить каталог", throwable)
                if (!received) {
                    _error.value =
                        "Не удалось загрузить каталог.\nПроверьте соединение и нажмите «Обновить»."
                }
            }

            _loading.value = false
        }
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}
