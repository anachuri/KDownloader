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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
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
import com.app.kdownloader.platform.DownloadEvent
import com.app.kdownloader.platform.DownloadRequest
import com.app.kdownloader.platform.YT_DLP_UNSUPPORTED_MESSAGE
import com.app.kdownloader.platform.YtDlpInfo
import com.app.kdownloader.platform.defaultDownloadDirectory
import com.app.kdownloader.platform.fetchImageBytes
import com.app.kdownloader.platform.probeYtDlp
import com.app.kdownloader.platform.revealInFolder
import com.app.kdownloader.platform.supportsYtDlpDownloads
import com.app.kdownloader.platform.ytDlpDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.roundToInt

private const val YOUTUBE_SAMPLE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
private const val VIMEO_SAMPLE = "https://vimeo.com/76979871"

/** What the user is currently choosing to download. Only one can be active at a time. */
private enum class DownloadType(val label: String, val glyph: String) {
    Video("Video", "▶"),
    Audio("Audio", "♪"),
}

/** UI state for the running/recent yt-dlp download. */
private data class DownloadUiState(
    val label: String,
    val inProgress: Boolean,
    val fraction: Float?,
    val detail: String,
    val log: List<String>,
    val success: Boolean? = null,
    val outputPath: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KDownloaderScreen() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<VideoAnalysis?>(null) }
    var selectedType by remember { mutableStateOf(DownloadType.Video) }
    var selectedVideo by remember { mutableStateOf(0) }
    var selectedAudio by remember { mutableStateOf(0) }

    var ytDlpInfo by remember { mutableStateOf<YtDlpInfo?>(null) }
    var downloadDirectory by remember { mutableStateOf("") }
    var downloadState by remember { mutableStateOf<DownloadUiState?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        ytDlpInfo = runCatching { probeYtDlp() }.getOrNull()
        downloadDirectory = defaultDownloadDirectory()
    }

    fun startDownload(request: DownloadRequest, label: String) {
        if (!supportsYtDlpDownloads) {
            scope.launch { snackbarHostState.showSnackbar(YT_DLP_UNSUPPORTED_MESSAGE) }
            return
        }
        if (downloadJob?.isActive == true) return

        downloadState = DownloadUiState(
            label = label,
            inProgress = true,
            fraction = null,
            detail = "Iniciando yt-dlp…",
            log = emptyList(),
        )
        downloadJob = scope.launch {
            try {
                ytDlpDownload(request).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> downloadState = downloadState?.copy(
                            fraction = event.fraction,
                            detail = describeProgress(event),
                        )

                        is DownloadEvent.Log -> downloadState = downloadState?.let { state ->
                            state.copy(log = (state.log + event.line).takeLast(60))
                        }

                        is DownloadEvent.Finished -> downloadState = downloadState?.let { state ->
                            state.copy(
                                inProgress = false,
                                fraction = if (event.success) 1f else state.fraction,
                                detail = event.message,
                                success = event.success,
                                outputPath = event.outputPath,
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                downloadState = downloadState?.copy(
                    inProgress = false,
                    success = false,
                    detail = "Error inesperado al descargar: ${error.message ?: error::class.simpleName}",
                )
            }
        }
    }

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
                selectedType = DownloadType.Video
                selectedVideo = result.videoOptions.indexOfFirst { it.recommended }.coerceAtLeast(0)
                selectedAudio = 0
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                            "Calidades estimadas de la demo: la descarga sí usa yt-dlp con el enlace real.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))

                        DownloadOptionsCard(
                            result = result,
                            type = selectedType,
                            onTypeChange = { selectedType = it },
                            selectedVideo = selectedVideo,
                            selectedAudio = selectedAudio,
                            onSelectVideo = { selectedVideo = it },
                            onSelectAudio = { selectedAudio = it },
                        )

                        Spacer(Modifier.height(22.dp))
                        DownloadActions(
                            result = result,
                            type = selectedType,
                            selectedVideo = selectedVideo,
                            selectedAudio = selectedAudio,
                            directory = downloadDirectory,
                            onDirectoryChange = { downloadDirectory = it },
                            info = ytDlpInfo,
                            isDownloading = downloadState?.inProgress == true,
                            onDownload = {
                                when (selectedType) {
                                    DownloadType.Video -> {
                                        val video = result.videoOptions
                                            .getOrNull(selectedVideo)
                                            ?: result.videoOptions.first()
                                        startDownload(
                                            request = DownloadRequest(
                                                url = result.url,
                                                outputDirectory = downloadDirectory,
                                                maxHeight = video.maxHeightOrNull(),
                                            ),
                                            label = "Video ${video.label}",
                                        )
                                    }

                                    DownloadType.Audio -> {
                                        val audio = result.audioOptions
                                            .getOrNull(selectedAudio)
                                            ?: result.audioOptions.first()
                                        startDownload(
                                            request = DownloadRequest(
                                                url = result.url,
                                                outputDirectory = downloadDirectory,
                                                audioFormat = audio.audioFormat(),
                                                audioBitrateKbps = audio.bitrateKbpsOrNull(),
                                            ),
                                            label = "Audio ${audio.label}",
                                        )
                                    }
                                }
                            },
                        )

                        downloadState?.let { state ->
                            Spacer(Modifier.height(16.dp))
                            DownloadProgressCard(
                                state = state,
                                onCancel = {
                                    downloadJob?.cancel()
                                    downloadJob = null
                                    downloadState = downloadState?.copy(
                                        inProgress = false,
                                        success = false,
                                        detail = "Descarga cancelada por el usuario.",
                                    )
                                },
                                onDismiss = { downloadState = null },
                                onReveal = { path ->
                                    scope.launch {
                                        val opened = runCatching { revealInFolder(path) }
                                            .getOrDefault(false)
                                        snackbarHostState.showSnackbar(
                                            if (opened) {
                                                "Abriendo la carpeta…"
                                            } else {
                                                "No se pudo abrir la carpeta del archivo."
                                            },
                                        )
                                    }
                                },
                            )
                        }
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
            .clip(RoundedCornerShape(10.dp))
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
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
        shape = RoundedCornerShape(10.dp),
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
        shape = RoundedCornerShape(10.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
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
            MaterialTheme.colorScheme.outlineVariant,
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
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
            shape = RoundedCornerShape(10.dp),
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
            .clip(RoundedCornerShape(16.dp))
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
private fun DownloadOptionsCard(
    result: VideoAnalysis,
    type: DownloadType,
    onTypeChange: (DownloadType) -> Unit,
    selectedVideo: Int,
    selectedAudio: Int,
    onSelectVideo: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVideo = type == DownloadType.Video
    val optionCount = if (isVideo) result.videoOptions.size else result.audioOptions.size

    OptionCard(
        title = type.label,
        subtitle = if (isVideo) {
            "$optionCount resoluciones disponibles"
        } else {
            "$optionCount formatos disponibles"
        },
        glyph = type.glyph,
        accent = if (isVideo) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        modifier = modifier,
    ) {
        DownloadTypeSelector(type = type, onTypeChange = onTypeChange)
        Spacer(Modifier.height(14.dp))
        if (isVideo) {
            result.videoOptions.forEachIndexed { index, option ->
                QualityRow(
                    selected = selectedVideo == index,
                    onClick = { onSelectVideo(index) },
                    title = option.label,
                    badge = option.quality,
                    subtitle = option.format,
                    trailing = option.size,
                    recommended = option.recommended,
                )
                if (index != result.videoOptions.lastIndex) Spacer(Modifier.height(8.dp))
            }
        } else {
            result.audioOptions.forEachIndexed { index, option ->
                QualityRow(
                    selected = selectedAudio == index,
                    onClick = { onSelectAudio(index) },
                    title = option.label,
                    subtitle = option.detail,
                    trailing = option.size,
                )
                if (index != result.audioOptions.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DownloadTypeSelector(
    type: DownloadType,
    onTypeChange: (DownloadType) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DownloadType.entries.forEach { option ->
                val selected = option == type
                val shape = RoundedCornerShape(8.dp)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .clickable { onTypeChange(option) },
                    shape = shape,
                    color = if (selected) colors.surface else Color.Transparent,
                    shadowElevation = if (selected) 1.dp else 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.glyph,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) colors.primary else colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                        )
                    }
                }
            }
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
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
    val shape = RoundedCornerShape(10.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            colors.primaryContainer
        } else {
            colors.surfaceVariant.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary else colors.outlineVariant,
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
    type: DownloadType,
    selectedVideo: Int,
    selectedAudio: Int,
    directory: String,
    onDirectoryChange: (String) -> Unit,
    info: YtDlpInfo?,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    val video = result.videoOptions.getOrNull(selectedVideo) ?: result.videoOptions.first()
    val audio = result.audioOptions.getOrNull(selectedAudio) ?: result.audioOptions.first()
    val buttonLabel = if (isDownloading) "Descargando…" else "Descargar ${type.label.lowercase()}"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            DownloadSummary(type = type, video = video, audio = audio)
            Spacer(Modifier.height(12.dp))
            val fixedDirectory = info?.fixedDownloadDirectory
            if (fixedDirectory != null) {
                Text(
                    text = "Los archivos se guardan en $fixedDirectory",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = directory,
                    onValueChange = onDirectoryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isDownloading,
                    shape = RoundedCornerShape(10.dp),
                    label = { Text("Carpeta de destino") },
                )
            }
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onDownload,
                enabled = !isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(buttonLabel, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))
            DownloadToolStatus(info = info, isDownloading = isDownloading)
        }
    }
}

@Composable
private fun DownloadToolStatus(info: YtDlpInfo?, isDownloading: Boolean) {
    val colors = MaterialTheme.colorScheme
    val problem = info != null &&
        (!info.platformSupported || info.error != null || info.executable == null)
    val text = when {
        info == null -> "Comprobando yt-dlp…"
        !info.platformSupported -> YT_DLP_UNSUPPORTED_MESSAGE
        info.error != null -> info.error
        info.executable == null -> "yt-dlp no está disponible."
        else -> {
            val version = info.version?.let { " $it" }.orEmpty()
            val ffmpeg = if (info.ffmpegAvailable) "ffmpeg detectado" else "ffmpeg no instalado"
            "yt-dlp$version listo · $ffmpeg"
        }
    }

    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = if (problem) colors.error else colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    if (info?.executable != null && !info.ffmpegAvailable && !isDownloading) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Sin ffmpeg solo se descargan archivos combinados y el audio se guarda en su " +
                "formato original. Instala ffmpeg para unir pistas y convertir a MP3.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadProgressCard(
    state: DownloadUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onReveal: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = when (state.success) {
        true -> colors.primary
        false -> colors.error
        null -> colors.secondary
    }
    val logScroll = rememberScrollState()
    LaunchedEffect(state.log.size) {
        logScroll.scrollTo(logScroll.maxValue)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (state.success) {
                            true -> "✓"
                            false -> "✕"
                            null -> "↓"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        state.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (state.inProgress) {
                    TextButton(onClick = onCancel) { Text("Cancelar") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            }

            if (state.inProgress) {
                Spacer(Modifier.height(12.dp))
                val fraction = state.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50)),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50)),
                    )
                }
            }

            state.outputPath?.let { path ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = colors.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        path,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.success == true) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onReveal(path) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Mostrar en carpeta", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .verticalScroll(logScroll)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = state.log.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun DownloadSummary(
    type: DownloadType,
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
            when (type) {
                DownloadType.Video -> "Video ${video.label} · ${video.size}"
                DownloadType.Audio -> "Audio ${audio.label} · ${audio.size}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun VideoQuality.maxHeightOrNull(): Int? =
    label.removeSuffix("p").trim().toIntOrNull()

private fun AudioQuality.audioFormat(): String? =
    label.substringBefore('·').trim().lowercase().takeIf { it.isNotEmpty() }

private fun AudioQuality.bitrateKbpsOrNull(): Int? =
    Regex("""(\d+)\s*kbps""", RegexOption.IGNORE_CASE)
        .find(label)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private fun describeProgress(progress: DownloadEvent.Progress): String {
    val parts = buildList {
        progress.fraction?.let { add("${(it * 100).roundToInt()}%") }
        progress.speed?.let { add(it) }
        progress.eta?.let { add("ETA $it") }
        if (isEmpty()) progress.downloadedBytes?.let { add(formatBytes(it)) }
    }
    return parts.joinToString(" · ").ifEmpty { "Descargando…" }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${oneDecimal(bytes / 1_000_000_000.0)} GB"
    bytes >= 1_000_000 -> "${oneDecimal(bytes / 1_000_000.0)} MB"
    bytes >= 1_000 -> "${oneDecimal(bytes / 1_000.0)} KB"
    else -> "$bytes B"
}

private fun oneDecimal(value: Double): String {
    val rounded = (value * 10).roundToInt()
    return "${rounded / 10},${rounded % 10}"
}
