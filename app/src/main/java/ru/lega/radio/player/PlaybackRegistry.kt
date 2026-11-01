package ru.lega.radio.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import ru.lega.radio.domain.Station
import ru.lega.radio.domain.Stream
import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр станций, которые сейчас в очереди.
 *
 * Нужен сервису: UI присылает только MediaItem с mediaId, а сервис по этому id
 * достаёт станцию целиком и может переключиться на резервный поток.
 * Живёт в памяти процесса, поэтому ничего не сериализуем.
 */
object PlaybackRegistry {
    private val stations = ConcurrentHashMap<String, Station>()

    fun put(station: Station) {
        if (station.streams.isNotEmpty()) stations[station.id] = station
    }

    fun stationOf(stationId: String): Station? = stations[stationId]
}

/** Сборка MediaItem из доменной модели станции. */
object PlaybackItems {

    fun build(station: Station, stream: Stream): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(stream.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.subtitle.takeIf { it.isNotBlank() })
                    .setArtworkUri(station.logoUrl?.let(Uri::parse))
                    .build(),
            )

        if (stream.isHls) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        return builder.build()
    }
}
