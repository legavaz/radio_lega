package ru.lega.radio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow as KotlinFlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import ru.lega.radio.R
import ru.lega.radio.domain.Station

@Composable
fun AppRoot(vm: RadioViewModel = viewModel()) {
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val stations by vm.visibleStations.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val currentTitle by vm.currentTitle.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val currentStation by vm.currentStation.collectAsState()
    val sleepRemaining by vm.sleepRemaining.collectAsState()
    val playbackError by vm.playbackError.collectAsState()

    var showPlayer by rememberSaveable { mutableStateOf(false) }

    // Лента: нужна, чтобы открыть приложение сразу на текущей станции.
    val listState = rememberLazyListState()
    var scrolledToCurrent by remember { mutableStateOf(false) }

    LaunchedEffect(currentId) {
        scrolledToCurrent = false
    }

    LaunchedEffect(currentId, stations.size) {
        if (scrolledToCurrent) return@LaunchedEffect
        val id = currentId ?: return@LaunchedEffect
        val index = stations.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect

        // Прокручиваем только если станция вне экрана — иначе список «прыгает»
        // на первое место при обычном тапе по станции.
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!isVisible) listState.scrollToItem(index)
        scrolledToCurrent = true
    }

    // Экономия заряда: без исключения система глушит фоновое воспроизведение.
    var batteryBannerHidden by rememberSaveable { mutableStateOf(false) }
    val needsBatteryExemption = !isIgnoringBatteryOptimizations(context)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.retry))
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterButton(
                        text = stringResource(R.string.russia),
                        selected = filter == CatalogFilter.RUSSIA,
                    ) { vm.setFilter(CatalogFilter.RUSSIA) }
                    FilterButton(
                        text = stringResource(R.string.all_stations),
                        selected = filter == CatalogFilter.ALL,
                    ) { vm.setFilter(CatalogFilter.ALL) }
                    FilterButton(
                        text = stringResource(R.string.favorites),
                        selected = filter == CatalogFilter.FAVORITES,
                    ) { vm.setFilter(CatalogFilter.FAVORITES) }
                }

                if (needsBatteryExemption && !batteryBannerHidden) {
                    BatteryBanner(
                        onAllow = { requestIgnoreBatteryOptimizations(context) },
                        onHide = { batteryBannerHidden = true },
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val errorText = error
                    when {
                        loading && stations.isEmpty() -> CircularProgressIndicator(
                            Modifier.align(Alignment.Center),
                        )

                        errorText != null && stations.isEmpty() -> Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(errorText, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                        }

                        stations.isEmpty() -> Text(
                            text = stringResource(
                                if (filter == CatalogFilter.FAVORITES) R.string.empty_favorites
                                else R.string.empty,
                            ),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )

                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(stations, key = { it.id }) { station ->
                                StationRow(
                                    station = station,
                                    isFavorite = station.id in favorites,
                                    isCurrent = station.id == currentId,
                                    onClick = { vm.play(station) },
                                    onToggleFavorite = { vm.toggleFavorite(station) },
                                )
                            }
                        }
                    }
                }

                val title = currentTitle
                if (title != null) {
                    MiniPlayer(
                        station = currentStation,
                        title = title,
                        isPlaying = isPlaying,
                        isFavorite = currentStation?.id?.let { it in favorites } ?: false,
                        sleepRemaining = sleepRemaining,
                        errorText = playbackError?.let { stringResource(R.string.stream_error) },
                        onToggle = vm::togglePlayPause,
                        onToggleFavorite = { currentStation?.let(vm::toggleFavorite) },
                        onOpen = { showPlayer = true },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }

            if (showPlayer && currentTitle != null) {
                PlayerOverlay(
                    title = currentTitle.orEmpty(),
                    isPlaying = isPlaying,
                    sleepRemaining = sleepRemaining,
                    onToggle = vm::togglePlayPause,
                    onSleepTimer = vm::setSleepTimer,
                    onClose = { showPlayer = false },
                )
            }
        }
    }
}

/**
 * Баннер про экономию заряда: показываем, только если приложение
 * ещё не исключено из оптимизации батареи. Скрывается по «Скрыть»,
 * а после выдачи разрешения исчезает сам.
 */
@Composable
private fun BatteryBanner(onAllow: () -> Unit, onHide: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.battery_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.battery_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onHide) {
                    Text(stringResource(R.string.battery_hide))
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onAllow) {
                    Text(stringResource(R.string.battery_allow))
                }
            }
        }
    }
}

@Composable
private fun FilterButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    if (selected) {
        Button(onClick = onClick, contentPadding = padding) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding) { Text(text) }
    }
}

@Composable
private fun StationRow(
    station: Station,
    isFavorite: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationLogo(station = station, size = 48)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val second = listOfNotNull(
                station.subtitle.takeIf { it.isNotBlank() },
                station.quality.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (second.isNotEmpty()) {
                Text(
                    text = second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.remove_favorite else R.string.add_favorite,
                ),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Логотип станции с запасными источниками.
 *
 * У многих станций (особенно российских) в Radio Browser пустой `favicon`,
 * но есть сайт. Поэтому пробуем по очереди:
 *   1) favicon из каталога,
 *   2) иконку по домену сайта (DuckDuckGo),
 *   3) то же через сервис Google,
 *   4) если ничего не вышло — букву на плашке.
 */
/**
 * Логотип станции с запасными источниками.
 *
 * У многих станций (особенно российских) в Radio Browser пустой `favicon`.
 * Поэтому пробуем по очереди:
 *   1) favicon из каталога,
 *   2) иконку по домену сайта (DuckDuckGo),
 *   3) иконку по домену сайта (Google),
 *   4) иконку по домену самого аудиопотока,
 *   5) если ничего не вышло — цветную букву.
 */
@Composable
private fun StationLogo(station: Station, size: Int) {
    val candidates = remember(station) { logoCandidates(station) }
    var attempt by remember(station) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(attempt)

    val baseModifier = Modifier
        .size(size.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)

    if (url == null) {
        LetterLogo(name = station.name, modifier = baseModifier)
    } else {
        AsyncImage(
            model = url,
            contentDescription = station.name,
            modifier = baseModifier,
            // Иконки из сервисов маленькие — их лучше вписывать, а не обрезать.
            contentScale = if (attempt == 0) ContentScale.Crop else ContentScale.Fit,
            onError = { attempt += 1 },
        )
    }
}

/** Заглушка: первая буква названия на цветной плашке (цвет стабилен для станции). */
@Composable
private fun LetterLogo(name: String, modifier: Modifier) {
    val palette = listOf(
        Color(0xFF2F6F8F), Color(0xFF3F6A52), Color(0xFF7A4E8C),
        Color(0xFF8C5A3C), Color(0xFF4C5F8A), Color(0xFF7C4A4A),
    )
    val color = palette[Math.floorMod(name.hashCode(), palette.size)]

    Box(modifier.background(color), contentAlignment = Alignment.Center) {
        Text(
            text = name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

private fun logoCandidates(station: Station): List<String> {
    val result = mutableListOf<String>()

    station.logoUrl?.takeIf { it.isNotBlank() }?.let { result += it }

    val hosts = buildList {
        hostOf(station.homepage)?.let { add(it) }
        hostOf(station.streams.firstOrNull()?.url)?.let { add(it) }
    }.distinct()

    hosts.forEach { host ->
        result += "https://icons.duckduckgo.com/ip3/$host.ico"
        result += "https://www.google.com/s2/favicons?sz=128&domain=$host"
    }

    return result.distinct()
}

private fun hostOf(url: String?): String? = url
    ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    ?.removePrefix("www.")
    ?.takeIf { it.contains('.') }

@Composable
private fun MiniPlayer(
    station: Station?,
    title: String,
    isPlaying: Boolean,
    isFavorite: Boolean,
    sleepRemaining: Long?,
    errorText: String?,
    onToggle: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (station != null) {
                StationLogo(station = station, size = 40)
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val genre = station?.subtitle?.takeIf { it.isNotBlank() }
                    if (genre != null) {
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            sleepRemaining?.let { remaining -> SleepTimerBadge(remaining) }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.remove_favorite else R.string.add_favorite,
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.pause else R.string.play,
                    ),
                )
            }
        }
    }
}

/**
 * Индикатор работающего таймера сна в мини-плеере:
 * текущая фаза Луны + обратный отсчёт. По тапу открывается полный плеер,
 * где таймер можно поменять или снять.
 */
@Composable
private fun SleepTimerBadge(remainingMs: Long) {
    val fraction = moonPhaseFraction()
    val phaseDescription = stringResource(R.string.sleep_timer_phase, moonPhaseOf(fraction).title)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 4.dp, end = 2.dp)
            .semantics { contentDescription = phaseDescription },
    ) {
        MoonPhaseIcon(
            fraction = fraction,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = formatRemaining(remainingMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Луна в цвете темы, нарисованная вручную: контур диска + залитая
 * освещённая часть. Терминатор (граница света и тени) — эллипс,
 * сжатие которого задаётся фазой: в новолуние он совпадает с диском,
 * в полнолуние — вырожден в прямую.
 */
@Composable
private fun MoonPhaseIcon(
    fraction: Double,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        if (radius <= 0f) return@Canvas

        val topLeft = Offset(center.x - radius, center.y - radius)
        val discSize = Size(radius * 2f, radius * 2f)

        // Контур: в новолуние диск виден именно им.
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = radius - 0.5f,
            center = center,
            style = Stroke(width = (radius * 0.16f).coerceAtLeast(0.8f)),
        )

        val k = cos(2.0 * Math.PI * fraction).toFloat()   // +1 новолуние, -1 полнолуние
        val semiAxis = radius * kotlin.math.abs(k)        // полуось терминатора
        val waxing = fraction < 0.5
        val startAngle = if (waxing) -90f else 90f

        val terminatorRect = Rect(
            left = center.x - semiAxis,
            top = center.y - radius,
            right = center.x + semiAxis,
            bottom = center.y + radius,
        )

        when {
            semiAxis < 0.5f -> {
                // Ровно половина диска (первая/последняя четверть).
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = discSize,
                )
            }

            (waxing && k > 0f) || (!waxing && k < 0f) -> {
                // Серп: половина диска минус эллипс терминатора.
                val ellipse = Path().apply { addOval(terminatorRect) }
                clipPath(ellipse, ClipOp.Difference) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = discSize,
                    )
                }
            }

            else -> {
                // Горб: половина диска плюс эллипс терминатора.
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = discSize,
                )
                drawOval(
                    color = color,
                    topLeft = Offset(terminatorRect.left, terminatorRect.top),
                    size = Size(terminatorRect.width, terminatorRect.height),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerOverlay(
    title: String,
    isPlaying: Boolean,
    sleepRemaining: Long?,
    onToggle: () -> Unit,
    onSleepTimer: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var chosenMinutes by rememberSaveable { mutableIntStateOf(0) }

    // Таймер закончился или его сняли — сбрасываем подсветку чипа.
    LaunchedEffect(sleepRemaining) {
        if (sleepRemaining == null) chosenMinutes = 0
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                Text(
                    text = stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                IconButton(onClick = onToggle, modifier = Modifier.size(72.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.pause else R.string.play,
                        ),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.sleep_timer),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            KotlinFlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = 5,
            ) {
                TimerChip(
                    text = stringResource(R.string.sleep_off),
                    selected = chosenMinutes == 0,
                ) {
                    chosenMinutes = 0
                    onSleepTimer(0)
                }
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    TimerChip(
                        text = minutes.toString(),
                        selected = chosenMinutes == minutes,
                    ) {
                        chosenMinutes = minutes
                        onSleepTimer(minutes)
                    }
                }
            }

            sleepRemaining?.let { remaining ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.sleep_until, formatRemaining(remaining)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    if (selected) {
        Button(onClick = onClick, contentPadding = padding) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
