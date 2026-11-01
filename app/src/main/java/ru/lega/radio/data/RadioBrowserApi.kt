package ru.lega.radio.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import ru.lega.radio.domain.Station
import ru.lega.radio.domain.Stream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val TAG = "RadioBrowserApi"

/**
 * DNS, который предпочитает IPv4.
 *
 * На части устройств IPv6 «объявлен», но фактически недоступен
 * (нет маршрута), а radio-browser.info отдаёт AAAA-записи. Без этого
 * OkHttp идёт в IPv6, висит до connect-таймаута и запрос падает,
 * хотя по IPv4 всё работает за миллисекунды.
 */
private object Ipv4Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 else resolved
    }
}

/**
 * Клиент открытого каталога Radio Browser (radio-browser.info).
 *
 * Два важных ограничения, найденных на практике:
 *
 * 1. Сервер отдаёт ответ через chunked и обрывает поток на ~18 КБ,
 *    если запросить сразу много станций. Поэтому грузим страницами
 *    по [PAGE_SIZE] с параметром `offset`.
 * 2. Заголовок User-Agent обязателен — без него сервер может ответить 403.
 */
class RadioBrowserApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .dns(Ipv4Dns)
        // На части роутеров HTTP/2 «зависает»: заголовки приходят,
        // а тело ответа не читается. Для обычного REST это лишний риск.
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class StationDto(
        val stationuuid: String = "",
        val name: String = "",
        val url_resolved: String = "",
        val url: String = "",
        val favicon: String = "",
        val homepage: String = "",
        val tags: String = "",
        val country: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val hls: Int = 0,
    )

    // Актуальные зеркала Radio Browser на 2026-11: остальные (nl1, at1, fr1)
    // больше не резолвятся в DNS. Держим два имени на случай переезда.
    private val mirrors = listOf("all", "de1")

    /**
     * Загружает каталог страницами и отдаёт список по мере поступления.
     *
     * Сервер Radio Browser нестабилен на больших ответах, поэтому:
     *  - страница маленькая ([PAGE_SIZE]);
     *  - неудачная страница повторяется один раз и пропускается;
     *  - каждый успешный шаг сразу отдаётся наверх, чтобы список
     *    наполнялся на глазах, а не после всех запросов.
     */
    fun topStationsFlow(countryCode: String? = null, target: Int = 120): Flow<List<Station>> = flow {
        val collected = LinkedHashMap<String, Station>()
        val pages = (target + PAGE_SIZE - 1) / PAGE_SIZE
        var firstError: Throwable? = null

        repeat(pages) { index ->
            val offset = index * PAGE_SIZE
            val page = fetchPageWithRetry(offset, countryCode)
            if (page == null) {
                if (firstError == null) firstError = IllegalStateException("Страница $offset недоступна")
                return@repeat
            }
            if (page.isEmpty()) return@repeat

            page.forEach { dto -> dto.toStation()?.let { collected[it.id] = it } }
            emit(collected.values.toList())
        }

        if (collected.isEmpty()) {
            throw firstError ?: IllegalStateException("Каталог пуст")
        }
    }.flowOn(Dispatchers.IO)

    /** Одна повторная попытка: сервер периодически обрывает поток. */
    private fun fetchPageWithRetry(offset: Int, countryCode: String?): List<StationDto>? {
        repeat(2) { attempt ->
            try {
                return fetchPage(offset = offset, limit = PAGE_SIZE, countryCode = countryCode)
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Страница offset=$offset, попытка ${attempt + 1}: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
        return null
    }

    private fun fetchPage(offset: Int, limit: Int, countryCode: String?): List<StationDto> {
        var lastError: Throwable? = null

        for (mirror in mirrors) {
            try {
                val url = buildString {
                    append("https://")
                    append(mirror)
                    append(".api.radio-browser.info/json/stations/search")
                    append("?hidebroken=true&order=clickcount&reverse=true")
                    append("&limit=").append(limit)
                    append("&offset=").append(offset)
                    if (countryCode != null) append("&countrycode=").append(countryCode)
                    append("&fields=").append(FIELDS)
                }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} от $mirror")
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) error("пустой ответ от $mirror")
                    return json.decodeFromString<List<StationDto>>(body)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Зеркало $mirror, offset=$offset: ${t.javaClass.simpleName}: ${t.message}")
                lastError = t
            }
        }

        throw lastError ?: IllegalStateException("Все зеркала Radio Browser недоступны")
    }

    private fun StationDto.toStation(): Station? {
        val resolved = url_resolved.trim().ifBlank { url.trim() }
        if (resolved.isBlank()) return null

        return Station(
            id = stationuuid.ifBlank { resolved },
            name = name.trim().replace(Regex("\\s+"), " ").ifBlank { "Без названия" },
            genre = tags.substringBefore(',').trim().takeIf { it.isNotEmpty() },
            country = country.trim().takeIf { it.isNotEmpty() },
            logoUrl = favicon.trim().takeIf { it.startsWith("http") },
            homepage = homepage.trim().takeIf { it.startsWith("http") },
            streams = listOf(
                Stream(
                    url = resolved,
                    bitrate = bitrate.takeIf { it > 0 },
                    codec = codec.trim().takeIf { it.isNotEmpty() },
                    isHls = hls == 1 || resolved.contains(".m3u8", ignoreCase = true),
                ),
            ),
        )
    }

    private companion object {
        const val PAGE_SIZE = 12
        const val USER_AGENT = "LegaRadio/0.1 (https://example.invalid)"
        const val FIELDS = "stationuuid,name,url_resolved,favicon,homepage,tags,country,codec,bitrate,hls"
    }
}
