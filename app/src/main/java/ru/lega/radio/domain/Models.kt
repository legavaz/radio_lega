package ru.lega.radio.domain

import kotlinx.serialization.Serializable

/**
 * Один поток станции. У большинства станций он один, но модель сразу
 * рассчитана на резервные адреса — если основной не отвечает.
 */
@Serializable
data class Stream(
    val url: String,
    val bitrate: Int? = null,
    val codec: String? = null,
    val isHls: Boolean = false,
)

@Serializable
data class Station(
    val id: String,
    val name: String,
    val genre: String? = null,
    val country: String? = null,
    val logoUrl: String? = null,
    val homepage: String? = null,
    val streams: List<Stream> = emptyList(),
    /** true — станция добавлена вручную, а не получена из каталога. */
    val custom: Boolean = false,
) {
    val subtitle: String
        get() = listOfNotNull(genre, country).joinToString(" · ")

    val quality: String
        get() {
            val stream = streams.firstOrNull() ?: return ""
            return listOfNotNull(
                stream.codec,
                stream.bitrate?.let { "$it kbps" },
                if (stream.isHls) "HLS" else null,
            ).joinToString(" · ")
        }
}
