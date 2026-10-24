package com.app.kdownloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.kdownloader.model.AudioQuality
import com.app.kdownloader.model.VideoAnalysis
import com.app.kdownloader.model.VideoQuality
import com.app.kdownloader.model.analyzeLink
import com.app.kdownloader.platform.fetchImageBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.decodeToImageBitmap

private const val YOUTUBE_SAMPLE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
private const val VIMEO_SAMPLE = "https://vimeo.com/76979871"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KDownloaderScreen() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<VideoAnalysis?>(null) }
    var selectedVideo by remember { mutableStateOf(0) }
    var selectedAudio by remember { mutableStateOf(0) }

    fun analyze(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            error = "Pega un enlace para analizarlo."
            return
        }
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            error = "El enlace debe comenzar con http:// o https://"
            return
        }

        error = null
        isLoading = true
        analysis = null
        scope.launch {
            delay(850)
            val result = analyzeLink(trimmed)
            isLoading = false
            if (result == null) {
                error = "No pudimos reconocer ese enlace. Revisa que sea una URL válida."
            } else {
                analysis = result
                selectedVideo = result.videoOptions.indexOfFirst { it.recommended }.coerceAtLeast(0)
                selectedAudio = 0
            }
        }
    }

    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(background, primary, 0.14f),
                        lerp(background, primary, 0.03f),
                        background,
                    ),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LogoMark()
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "KDownloader",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Descarga en la mejor calidad",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1040.dp)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Hero()
                    Spacer(Modifier.height(22.dp))

                    SearchCard(
                        url = url,
                        isLoading = isLoading,
                        hasResult = analysis != null,
                        error = error,
                        onUrlChange = {
                            url = it
                            if (error != null) error = null
                        },
                        onClear = {
                            url = ""
                            analysis = null
                            error = null
                        },
                        onAnalyze = { analyze(url) },
                        onExample = { example ->
                            url = example
                            analyze(example)
                        },
                    )

                    if (analysis == null && !isLoading && error == null) {
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HintPill("Hasta 4K")
                            HintPill("MP3, M4A y Opus")
                            HintPill("Vista previa del video")
                        }
                    }

                    if (isLoading) {
                        Spacer(Modifier.height(18.dp))
                        LoadingCard()
                    }

                    analysis?.let { result ->
                        Spacer(Modifier.height(24.dp))
                        ResultHeader(result)
                        Spacer(Modifier.height(26.dp))

                        Text(
                            "Elige qué descargar",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Datos de ejemplo: esta demo todavía no consulta el servicio real del video.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))

                        BoxWithConstraints {
                            if (maxWidth >= 820.dp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    VideoOptionsCard(
                                        result = result,
                                        selected = selectedVideo,
                                        onSelect = { selectedVideo = it },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AudioOptionsCard(
                                        result = result,
                                        selected = selectedAudio,
                                        onSelect = { selectedAudio = it },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    VideoOptionsCard(
                                        result = result,
                                        selected = selectedVideo,
                                        onSelect = { selectedVideo = it },
                                    )
                                    AudioOptionsCard(
                                        result = result,
                                        selected = selectedAudio,
                                        onSelect = { selectedAudio = it },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(22.dp))
                        DownloadActions(
                            result = result,
                            selectedVideo = selectedVideo,
                            selectedAudio = selectedAudio,
                            onDownloadVideo = { video ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Demo: se descargaría el video en ${video.label} (${video.format}).",
                                    )
                                }
                            },
                            onDownloadAudio = { audio ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Demo: se descargaría el audio en ${audio.label} (${audio.detail}).",
                                    )
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                    Text(
                        "KDownloader · Interfaz multiplataforma",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun LogoMark() {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "↓",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun Hero() {
    Text(
        "Tu video, en todas las calidades.",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Pega un enlace y compara resoluciones de video y formatos de audio antes de descargar.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchCard(
    url: String,
    isLoading: Boolean,
    hasResult: Boolean,
    error: String?,
    onUrlChange: (String) -> Unit,
    onClear: () -> Unit,
    onAnalyze: () -> Unit,
    onExample: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Enlace del video",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            BoxWithConstraints {
                val stacked = maxWidth < 620.dp
                if (stacked) {
                    Column {
                        LinkField(
                            url = url,
                            onUrlChange = onUrlChange,
                            onClear = onClear,
                            onAnalyze = onAnalyze,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalyzeButton(
                            isLoading = isLoading,
                            hasResult = hasResult,
                            onClick = onAnalyze,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinkField(
                            url = url,
                            onUrlChange = onUrlChange,
                            onClear = onClear,
                            onAnalyze = onAnalyze,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        AnalyzeButton(
                            isLoading = isLoading,
                            hasResult = hasResult,
                            onClick = onAnalyze,
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionChip(
                    onClick = { onExample(YOUTUBE_SAMPLE) },
                    label = { Text("Ejemplo de YouTube") },
                )
                SuggestionChip(
                    onClick = { onExample(VIMEO_SAMPLE) },
                    label = { Text("Ejemplo de Vimeo") },
                )
            }
        }
    }
}

@Composable
private fun LinkField(
    url: String,
    onUrlChange: (String) -> Unit,
    onClear: () -> Unit,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = { Text("https://www.youtube.com/watch?v=…") },
        trailingIcon = if (url.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    Text("✕", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onAnalyze() }),
    )
}

@Composable
private fun AnalyzeButton(
    isLoading: Boolean,
    hasResult: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(56.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(10.dp))
            Text("Analizando…")
        } else {
            Text(
                if (hasResult) "Analizar de nuevo" else "Analizar enlace",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HintPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f),
        ),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Analizando enlace…",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Buscando resoluciones de video y formatos de audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun ResultHeader(result: VideoAnalysis) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.padding(18.dp)) {
            if (maxWidth >= 700.dp) {
                Row {
                    VideoThumbnail(
                        thumbnailUrl = result.thumbnailUrl,
                        duration = result.duration,
                        modifier = Modifier.width(360.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    ResultDetails(result = result, modifier = Modifier.weight(1f))
                }
            } else {
                Column {
                    VideoThumbnail(
                        thumbnailUrl = result.thumbnailUrl,
                        duration = result.duration,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    ResultDetails(result = result)
                }
            }
        }
    }
}

@Composable
private fun ResultDetails(result: VideoAnalysis, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                text = result.platform,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Pill(
                text = "Datos de ejemplo",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            result.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${result.host} · ${result.duration}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Metric(value = "${result.videoOptions.size}", label = "resoluciones")
            Metric(value = "${result.audioOptions.size}", label = "formatos de audio")
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                result.url,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VideoThumbnail(
    thumbnailUrl: String?,
    duration: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(thumbnailUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(thumbnailUrl) { mutableStateOf(thumbnailUrl != null) }

    LaunchedEffect(thumbnailUrl) {
        bitmap = null
        if (thumbnailUrl == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val bytes = withTimeoutOrNull(12_000) {
            runCatching { fetchImageBytes(thumbnailUrl) }.getOrNull()
        }
        bitmap = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
        loading = false
    }

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF312E81),
                        Color(0xFF0EA5E9),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = "Miniatura del video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )

            bitmap == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayIcon(Modifier.size(18.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Vista previa no disponible",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }

            else -> Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                PlayIcon(Modifier.size(20.dp))
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                duration,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PlayIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val triangle = Path().apply {
            moveTo(size.width * 0.12f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width * 0.12f, size.height)
            close()
        }
        drawPath(triangle, Color.White)
    }
}

@Composable
private fun VideoOptionsCard(
    result: VideoAnalysis,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionCard(
        title = "Video",
        subtitle = "${result.videoOptions.size} resoluciones disponibles",
        glyph = "▶",
        accent = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        result.videoOptions.forEachIndexed { index, option ->
            QualityRow(
                selected = selected == index,
                onClick = { onSelect(index) },
                title = option.label,
                badge = option.quality,
                subtitle = option.format,
                trailing = option.size,
                recommended = option.recommended,
            )
            if (index != result.videoOptions.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AudioOptionsCard(
    result: VideoAnalysis,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionCard(
        title = "Audio",
        subtitle = "${result.audioOptions.size} formatos disponibles",
        glyph = "♪",
        accent = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    ) {
        result.audioOptions.forEachIndexed { index, option ->
            QualityRow(
                selected = selected == index,
                onClick = { onSelect(index) },
                title = option.label,
                subtitle = option.detail,
                trailing = option.size,
            )
            if (index != result.audioOptions.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        glyph,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun QualityRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String,
    trailing: String,
    badge: String? = null,
    recommended: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            colors.primaryContainer.copy(alpha = 0.55f)
        } else {
            colors.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            text = "Recomendado",
                            container = colors.secondaryContainer,
                            content = colors.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DownloadActions(
    result: VideoAnalysis,
    selectedVideo: Int,
    selectedAudio: Int,
    onDownloadVideo: (VideoQuality) -> Unit,
    onDownloadAudio: (AudioQuality) -> Unit,
) {
    val video = result.videoOptions.getOrNull(selectedVideo) ?: result.videoOptions.first()
    val audio = result.audioOptions.getOrNull(selectedAudio) ?: result.audioOptions.first()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.padding(18.dp)) {
            if (maxWidth >= 700.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadSummary(video = video, audio = audio, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = { onDownloadVideo(video) },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Descargar video", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalButton(
                        onClick = { onDownloadAudio(audio) },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Descargar audio", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Column {
                    DownloadSummary(video = video, audio = audio)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { onDownloadVideo(video) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Descargar video", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { onDownloadAudio(audio) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Descargar audio", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Demostración de interfaz: todavía no se descargan archivos.",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DownloadSummary(
    video: VideoQuality,
    audio: AudioQuality,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "Listo para descargar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Video ${video.label} · ${video.size}   ·   Audio ${audio.label} · ${audio.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
