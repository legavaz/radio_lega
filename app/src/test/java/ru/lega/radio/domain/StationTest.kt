package ru.lega.radio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Форматирование карточки станции — то, что видно в списке.
 * Логика чистая, проверяется без Android.
 */
class StationTest {

    private fun station(
        genre: String? = null,
        country: String? = null,
        streams: List<Stream> = emptyList(),
    ) = Station(
        id = "test",
        name = "Тест FM",
        genre = genre,
        country = country,
        streams = streams,
    )

    @Test
    fun `подпись собирается из жанра и страны`() {
        val s = station(genre = "rock", country = "Россия")
        assertEquals("rock · Россия", s.subtitle)
    }

    @Test
    fun `подпись без жанра содержит только страну`() {
        val s = station(country = "Россия")
        assertEquals("Россия", s.subtitle)
    }

    @Test
    fun `пустая подпись у станции без жанра и страны`() {
        assertEquals("", station().subtitle)
    }

    @Test
    fun `качество показывает кодек и битрейт`() {
        val s = station(
            streams = listOf(Stream(url = "https://x", bitrate = 128, codec = "MP3")),
        )
        assertEquals("MP3 · 128 kbps", s.quality)
    }

    @Test
    fun `качество помечает HLS`() {
        val s = station(streams = listOf(Stream(url = "https://x", codec = "AAC", isHls = true)))
        assertEquals("AAC · HLS", s.quality)
    }

    @Test
    fun `качество пустое, если нет потоков`() {
        assertEquals("", station().quality)
    }

    @Test
    fun `качество не падает на неизвестных полях`() {
        val s = station(streams = listOf(Stream(url = "https://x")))
        assertEquals("", s.quality)
    }
}
