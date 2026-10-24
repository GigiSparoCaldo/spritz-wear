package com.spritzwear.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class ActiveScreen {
    READER,
    CHAPTER_LIST,
    LIBRARY
}

private data class WordSpan(
    val globalIndex: Int,
    val startChar: Int,
    val endChar: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BookParser.getDedicatedFolders(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SpritzFlipApp()
            }
        }
    }
}

@Composable
fun SpritzFlipApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("spritz_prefs", Context.MODE_PRIVATE) }

    var currentBook by remember { mutableStateOf(BookParser.getDemoBook()) }
    var wordIndex by remember { mutableIntStateOf(prefs.getInt("saved_index_${currentBook.id}", 0)) }
    var wpm by remember { mutableIntStateOf(prefs.getInt("saved_wpm", 300)) }
    var isPlaying by remember { mutableStateOf(false) }
    var isTtsEnabled by remember { mutableStateOf(prefs.getBoolean("saved_tts", false)) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isVibrationEnabled by remember { mutableStateOf(prefs.getBoolean("saved_haptic", true)) }
    var isLoading by remember { mutableStateOf(false) }
    var activeScreen by remember { mutableStateOf(ActiveScreen.READER) }

    var wordsReadInSession by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    // Stato motore Text-to-Speech (TTS)
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    val currentChunkSpans = remember { mutableStateListOf<WordSpan>() }
    var currentUtteranceDone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var rangeCallbackTriggered by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.ITALIAN
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        rangeCallbackTriggered = true
                        val matched = currentChunkSpans.firstOrNull { span ->
                            start in span.startChar..span.endChar || span.startChar in start..end
                        }
                        if (matched != null) {
                            wordIndex = matched.globalIndex
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        currentUtteranceDone?.complete(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        currentUtteranceDone?.complete(Unit)
                    }
                })
                ttsEngine = engine
                isTtsReady = true
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    // Imposta automaticamente la lingua del TTS (Italiano o Inglese)
    LaunchedEffect(currentBook, isTtsReady) {
        if (isTtsReady) {
            val sampleText = currentBook.words.take(40).joinToString(" ").lowercase()
            val englishMarkers = listOf(" the ", " and ", " with ", " delivers ", " flashes ")
            val isEnglish = englishMarkers.count { sampleText.contains(it) } >= 2
            ttsEngine?.language = if (isEnglish) Locale.US else Locale.ITALIAN
        }
    }

    val loadFileAsync: (File) -> Unit = { file ->
        scope.launch {
            isLoading = true
            isPlaying = false
            ttsEngine?.stop()
            val loaded = withContext(Dispatchers.IO) { BookParser.parseFile(context, file) }
            currentBook = loaded
            wordIndex = prefs.getInt("saved_index_${loaded.id}", 0).coerceIn(0, loaded.words.lastIndex)
            isLoading = false
            activeScreen = ActiveScreen.READER
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                isLoading = true
                isPlaying = false
                ttsEngine?.stop()
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "Libro.epub"
                val loaded = withContext(Dispatchers.IO) {
                    BookParser.importUriToInternalStorage(context, it, name)
                }
                currentBook = loaded
                wordIndex = prefs.getInt("saved_index_${loaded.id}", 0).coerceIn(0, loaded.words.lastIndex)
                isLoading = false
                activeScreen = ActiveScreen.READER
            }
        }
    }

    LaunchedEffect(wordIndex, wpm, currentBook.id, isVibrationEnabled, isTtsEnabled) {
        prefs.edit()
            .putInt("saved_index_${currentBook.id}", wordIndex)
            .putInt("saved_total_${currentBook.id}", currentBook.words.size)
            .putInt("saved_wpm", wpm)
            .putBoolean("saved_haptic", isVibrationEnabled)
            .putBoolean("saved_tts", isTtsEnabled)
            .apply()
    }

    // Motore Duale: Lettura Automatica WPM oppure Voce TTS Sincronizzata
    LaunchedEffect(isPlaying, isTtsEnabled, currentBook) {
        if (!isPlaying) {
            ttsEngine?.stop()
            currentUtteranceDone?.complete(Unit)
            wordsReadInSession = 0
            return@LaunchedEffect
        }

        if (isTtsEnabled && isTtsReady && ttsEngine != null) {
            while (isPlaying && wordIndex <= currentBook.words.lastIndex) {
                val startIdx = wordIndex
                val spans = mutableListOf<WordSpan>()
                val sb = StringBuilder()
                var idx = startIdx

                while (idx <= currentBook.words.lastIndex && spans.size < 18) {
                    val w = currentBook.words[idx]
                    val s = sb.length
                    sb.append(w)
                    val e = sb.length
                    spans.add(WordSpan(idx, s, e))
                    sb.append(" ")
                    idx++
                    if (w.endsWith(".") || w.endsWith("!") || w.endsWith("?") || w.endsWith(";")) {
                        break
                    }
                }

                currentChunkSpans.clear()
                currentChunkSpans.addAll(spans)
                rangeCallbackTriggered = false

                val speechRate = (wpm / 180f).coerceIn(0.5f, 3.0f)
                ttsEngine?.setSpeechRate(speechRate)

                val doneDeferred = CompletableDeferred<Unit>()
                currentUtteranceDone = doneDeferred

                ttsEngine?.speak(
                    sb.toString().trim(),
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "SPRITZ_FLIP_$startIdx"
                )

                for (span in spans) {
                    if (!isPlaying || doneDeferred.isCompleted) break
                    if (!rangeCallbackTriggered) {
                        wordIndex = span.globalIndex
                    }
                    val spritz = BookParser.toSpritzWord(currentBook.words[span.globalIndex])
                    val effectiveTtsWpm = (180f * speechRate).toInt()
                    val stepDelay = BookParser.calculateDelayMs(effectiveTtsWpm, spritz.delayMultiplier)
                    delay(stepDelay)
                }

                doneDeferred.await()

                if (isPlaying) {
                    val nextIdx = spans.last().globalIndex + 1
                    if (nextIdx <= currentBook.words.lastIndex) {
                        wordIndex = nextIdx
                    } else {
                        isPlaying = false
                    }
                }
            }
        } else {
            while (isPlaying && wordIndex <= currentBook.words.lastIndex) {
                val currentRawWord = currentBook.words[wordIndex]
                val spritz = BookParser.toSpritzWord(currentRawWord)
                val ramp = (0.6f + (wordsReadInSession.coerceAtMost(12) / 12f) * 0.4f)
                val delayMs = BookParser.calculateDelayMs(wpm, spritz.delayMultiplier, ramp)

                delay(delayMs)
                if (wordIndex < currentBook.words.lastIndex) {
                    wordIndex++
                    wordsReadInSession++
                } else {
                    isPlaying = false
                }
            }
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFFE53935))
                Spacer(modifier = Modifier.height(10.dp))
                Text("Apertura libro...", color = Color.White, fontSize = 14.sp)
            }
        }
        return
    }

    when (activeScreen) {
        ActiveScreen.CHAPTER_LIST -> {
            ChapterListScreen(
                book = currentBook,
                currentWordIndex = wordIndex,
                onSelectChapter = { chapter ->
                    isPlaying = false
                    ttsEngine?.stop()
                    wordIndex = chapter.startWordIndex.coerceIn(0, currentBook.words.lastIndex)
                    activeScreen = ActiveScreen.READER
                },
                onClose = { activeScreen = ActiveScreen.READER }
            )
            return
        }

        ActiveScreen.LIBRARY -> {
            LibraryScreen(
                onSelectFile = { file -> loadFileAsync(file) },
                onSelectDemo = {
                    isPlaying = false
                    ttsEngine?.stop()
                    currentBook = BookParser.getDemoBook()
                    wordIndex = prefs.getInt("saved_index_${currentBook.id}", 0)
                        .coerceIn(0, currentBook.words.lastIndex)
                    activeScreen = ActiveScreen.READER
                },
                onOpenSystemPicker = {
                    runCatching {
                        filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*"))
                    }
                },
                onClose = { activeScreen = ActiveScreen.READER }
            )
            return
        }

        ActiveScreen.READER -> Unit
    }

    // Calcolo capitolo attuale e percentuale di completamento del singolo capitolo
    val currentChapterIndex = currentBook.chapters
        .indexOfLast { wordIndex >= it.startWordIndex }
        .coerceAtLeast(0)
    val currentChapter = currentBook.chapters.getOrElse(currentChapterIndex) {
        ChapterInfo("Capitolo 1", 0, currentBook.words.lastIndex)
    }
    val chapterTotalWords = (currentChapter.endWordIndex - currentChapter.startWordIndex).coerceAtLeast(1)
    val chapterDoneWords = (wordIndex - currentChapter.startWordIndex).coerceAtLeast(0)
    val chapterPercent = ((chapterDoneWords.toFloat() / chapterTotalWords.toFloat()) * 100f)
        .toInt()
        .coerceIn(0, 100)

    // Layout ottimizzato per la FlexWindow di Z Flip 7 / Z Flip 8 (con margine inferiore per le fotocamere)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. BARRA SUPERIORE CLICCABILE: Titolo Capitolo + Percentuale Capitolo (Apre Lista Capitoli)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161616))
                .clickable {
                    isPlaying = false
                    ttsEngine?.stop()
                    if (isVibrationEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    activeScreen = ActiveScreen.CHAPTER_LIST
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentChapter.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Seleziona Capitolo",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(18.dp)
                    )
                }
                val ttsRateText = if (isTtsEnabled) {
                    val rate = String.format(Locale.US, "%.1fx", (wpm / 180f).coerceIn(0.5f, 3.0f))
                    " • Voce $rate"
                } else ""
                Text(
                    text = "Capitolo al $chapterPercent% (${currentChapterIndex + 1}/${currentBook.chapters.size}) • $wpm WPM$ttsRateText",
                    color = if (isTtsEnabled) Color(0xFF81C784) else Color(0xFF9E9E9E),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        // 2. MIRINO SPRITZ CENTRALE (Toccando il mirino si avvia/ferma la lettura)
        SpritzReticleStage(
            words = currentBook.words,
            wordIndex = wordIndex,
            onTogglePlay = {
                if (isVibrationEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                if (wordIndex >= currentBook.words.lastIndex) wordIndex = 0
                isPlaying = !isPlaying
            }
        )

        // 3. PANNELLO CONTROLLI FLEXWINDOW (Trackpad manuale parole + Slider WPM + Pulsanti)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Trackpad Touch Orizzontale: scorri il dito a destra/sinistra per girare le parole manualmente
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A242D))
                    .pointerInput(isVibrationEnabled, currentBook) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                isPlaying = false
                                ttsEngine?.stop()
                                dragAccumulator = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragAccumulator += dragAmount
                                val stepPx = 26f
                                if (abs(dragAccumulator) >= stepPx) {
                                    val steps = (dragAccumulator / stepPx).toInt()
                                    dragAccumulator %= stepPx
                                    if (isVibrationEnabled) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    wordIndex = (wordIndex + steps).coerceIn(0, currentBook.words.lastIndex)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Swipe,
                        contentDescription = null,
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Scorri qui col dito per girare le parole",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Regolatore Velocità WPM (-25 | Slider 100..1000 WPM | +25)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SmallControlButton(
                    icon = Icons.Default.Remove,
                    contentDesc = "-25 WPM",
                    onClick = {
                        wpm = (wpm - 25).coerceIn(100, 1000)
                        if (isTtsEnabled && isPlaying) {
                            ttsEngine?.setSpeechRate((wpm / 180f).coerceIn(0.5f, 3.0f))
                        }
                    }
                )

                Slider(
                    value = wpm.toFloat(),
                    onValueChange = { newVal ->
                        val stepped = ((newVal.toInt() / 10) * 10).coerceIn(100, 1000)
                        if (stepped != wpm) {
                            wpm = stepped
                            if (isVibrationEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (isTtsEnabled && isPlaying) {
                                ttsEngine?.setSpeechRate((wpm / 180f).coerceIn(0.5f, 3.0f))
                            }
                        }
                    },
                    valueRange = 100f..1000f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFE53935),
                        activeTrackColor = Color(0xFFE53935),
                        inactiveTrackColor = Color(0xFF333333)
                    ),
                    modifier = Modifier.weight(1f)
                )

                SmallControlButton(
                    icon = Icons.Default.Add,
                    contentDesc = "+25 WPM",
                    onClick = {
                        wpm = (wpm + 25).coerceIn(100, 1000)
                        if (isTtsEnabled && isPlaying) {
                            ttsEngine?.setSpeechRate((wpm / 180f).coerceIn(0.5f, 3.0f))
                        }
                    }
                )
            }

            // Barra Azioni: Libreria | Cap. Prec | PLAY/PAUSA | Cap. Succ | Voce TTS | Vibrazione
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallControlButton(
                    icon = Icons.Default.FolderOpen,
                    contentDesc = "Libreria EPUB/TXT",
                    onClick = {
                        isPlaying = false
                        ttsEngine?.stop()
                        activeScreen = ActiveScreen.LIBRARY
                    }
                )

                SmallControlButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDesc = "Capitolo Precedente",
                    onClick = {
                        isPlaying = false
                        ttsEngine?.stop()
                        val prevIdx = if (wordIndex > currentChapter.startWordIndex + 3) {
                            currentChapter.startWordIndex
                        } else {
                            currentBook.chapters.getOrNull(currentChapterIndex - 1)?.startWordIndex ?: 0
                        }
                        wordIndex = prevIdx
                    }
                )

                // Pulsante Centrale Play / Pausa
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isTtsEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                        .clickable {
                            if (isVibrationEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            if (wordIndex >= currentBook.words.lastIndex) wordIndex = 0
                            isPlaying = !isPlaying
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pausa",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                SmallControlButton(
                    icon = Icons.Default.SkipNext,
                    contentDesc = "Capitolo Successivo",
                    onClick = {
                        isPlaying = false
                        ttsEngine?.stop()
                        val nextIdx = currentBook.chapters.getOrNull(currentChapterIndex + 1)?.startWordIndex
                            ?: currentBook.words.lastIndex
                        wordIndex = nextIdx
                    }
                )

                // Toggle Voce TTS On / Off
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isTtsEnabled) Color(0xFF1B5E20) else Color(0xFF262626))
                        .clickable {
                            isTtsEnabled = !isTtsEnabled
                            if (!isTtsEnabled) ttsEngine?.stop()
                            if (isVibrationEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isTtsEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Voce TTS On/Off",
                        tint = if (isTtsEnabled) Color(0xFF81C784) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Toggle Vibrazione On / Off
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isVibrationEnabled) Color(0xFF1E3A2F) else Color(0xFF262626))
                        .clickable {
                            isVibrationEnabled = !isVibrationEnabled
                            if (isVibrationEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isVibrationEnabled) Icons.Default.Vibration else Icons.Outlined.DoNotDisturbOn,
                        contentDescription = "Vibrazione On/Off",
                        tint = if (isVibrationEnabled) Color(0xFF81C784) else Color(0xFF757575),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SpritzReticleStage(
    words: List<String>,
    wordIndex: Int,
    onTogglePlay: () -> Unit
) {
    val word = words.getOrElse(wordIndex) { "" }
    val spritz = remember(word) { BookParser.toSpritzWord(word) }
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePlay() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Barra superiore con mirino circolare centrale (fedele al video)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(16.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            val circleRadius = 5.5.dp.toPx()

            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(centerX - circleRadius, centerY),
                strokeWidth = 1.6.dp.toPx()
            )
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.6.dp.toPx())
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX + circleRadius, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.6.dp.toPx()
            )
        }

        // 2. Parola su Canvas con allineamento ORP rosso e ridimensionamento anti-taglio
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (word.isEmpty()) return@Canvas

            val safePadding = 10.dp.toPx()
            val availableWidth = size.width - (safePadding * 2f)
            val centerX = size.width / 2f

            var fontSizeSp = when {
                word.length <= 9 -> 30f
                word.length in 10..13 -> 26f
                word.length in 14..17 -> 22f
                else -> 18f
            }

            val annotatedWord = buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Medium)) {
                    append(spritz.prefix)
                }
                withStyle(SpanStyle(color = Color(0xFFE53935), fontWeight = FontWeight.Bold)) {
                    append(spritz.pivot)
                }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Medium)) {
                    append(spritz.suffix)
                }
            }

            var layoutResult = textMeasurer.measure(
                text = annotatedWord,
                style = TextStyle(
                    fontSize = fontSizeSp.sp,
                    fontFamily = FontFamily.Monospace
                ),
                softWrap = false,
                maxLines = 1
            )

            while (layoutResult.size.width > availableWidth && fontSizeSp > 12f) {
                fontSizeSp -= 1f
                layoutResult = textMeasurer.measure(
                    text = annotatedWord,
                    style = TextStyle(
                        fontSize = fontSizeSp.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    softWrap = false,
                    maxLines = 1
                )
            }

            val pivotCharIndex = spritz.prefix.length
            val pivotBox = layoutResult.getBoundingBox(pivotCharIndex)
            val pivotCenterInText = (pivotBox.left + pivotBox.right) / 2f

            var drawX = centerX - pivotCenterInText

            val rightEdge = drawX + layoutResult.size.width
            if (rightEdge > size.width - safePadding) {
                drawX -= (rightEdge - (size.width - safePadding))
            }
            if (drawX < safePadding) {
                drawX = safePadding
            }

            val drawY = (size.height - layoutResult.size.height) / 2f

            drawText(
                textLayoutResult = layoutResult,
                topLeft = Offset(drawX, drawY)
            )
        }

        // 3. Barra inferiore con tacca rossa
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(10.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.6.dp.toPx()
            )
            drawLine(
                color = Color(0xFFE53935),
                start = Offset(centerX, centerY - 4.dp.toPx()),
                end = Offset(centerX, centerY),
                strokeWidth = 1.6.dp.toPx()
            )
        }

        // Anteprima del contesto della frase intorno alla parola attiva
        if (words.isNotEmpty()) {
            val startCtx = (wordIndex - 4).coerceAtLeast(0)
            val endCtx = (wordIndex + 4).coerceAtMost(words.lastIndex)
            val annotatedContext = buildAnnotatedString {
                for (i in startCtx..endCtx) {
                    if (i == wordIndex) {
                        withStyle(SpanStyle(color = Color(0xFFE53935), fontWeight = FontWeight.Bold)) {
                            append("${words[i]} ")
                        }
                    } else {
                        withStyle(SpanStyle(color = Color(0xFF808080))) {
                            append("${words[i]} ")
                        }
                    }
                }
            }
            Text(
                text = annotatedContext,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 8.dp, end = 8.dp)
            )
        }
    }
}

@Composable
fun ChapterListScreen(
    book: BookItem,
    currentWordIndex: Int,
    onSelectChapter: (ChapterInfo) -> Unit,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CAPITOLI (${book.chapters.size})",
                color = Color(0xFFE53935),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2C2C2C))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Chiudi", color = Color.White, fontSize = 12.sp)
            }
        }

        book.chapters.forEachIndexed { idx, chapter ->
            val isCurrent = currentWordIndex in chapter.startWordIndex..chapter.endWordIndex
            val chapPercent = when {
                currentWordIndex > chapter.endWordIndex -> 100
                currentWordIndex < chapter.startWordIndex -> 0
                else -> {
                    val total = (chapter.endWordIndex - chapter.startWordIndex).coerceAtLeast(1)
                    val done = (currentWordIndex - chapter.startWordIndex).coerceAtLeast(0)
                    ((done.toFloat() / total.toFloat()) * 100f).toInt().coerceIn(0, 100)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isCurrent) Color(0xFF3E1818) else Color(0xFF1C1C1C))
                    .clickable { onSelectChapter(chapter) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${idx + 1}. ${chapter.title}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$chapPercent%",
                    color = if (isCurrent) Color(0xFFFF8A80) else Color(0xFF9E9E9E),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun SmallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xFF262626))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun LibraryScreen(
    onSelectFile: (File) -> Unit,
    onSelectDemo: () -> Unit,
    onOpenSystemPicker: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var summaries by remember { mutableStateOf<List<BookSummary>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var wifiUrl by remember { mutableStateOf<String?>(null) }
    var hasAllFilesPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else true
        )
    }

    val refreshLibrary: () -> Unit = {
        scope.launch {
            isScanning = true
            summaries = withContext(Dispatchers.IO) { BookParser.getBookSummaries(context) }
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        refreshLibrary()
    }

    val wifiServer = remember {
        WifiBookServer(context) { uploadedFile ->
            onSelectFile(uploadedFile)
        }
    }

    DisposableEffect(Unit) {
        onDispose { wifiServer.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIBRERIA SPRITZBOOKS",
                color = Color(0xFFE53935),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3E1818))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Chiudi", color = Color.White, fontSize = 12.sp)
            }
        }

        // Pulsante Scansione / Permesso /sdcard/SpritzBooks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF263238))
                    .clickable {
                        BookParser.getDedicatedFolders(context)
                        hasAllFilesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Environment.isExternalStorageManager()
                        } else true

                        if (!hasAllFilesPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }
                        }
                        refreshLibrary()
                    }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scansiona",
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (hasAllFilesPermission) "Scansiona (${summaries.size})"
                        else "Permesso /SpritzBooks",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E2A38))
                    .clickable { onOpenSystemPicker() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Sfoglia File", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Lista dei Libri con Copertina EPUB e Percentuale di Completamento
        if (isScanning) {
            Text("Lettura copertine...", color = Color.Gray, fontSize = 12.sp)
        } else if (summaries.isEmpty()) {
            Text(
                text = "Copia i tuoi .epub o .txt nella cartella /sdcard/SpritzBooks oppure usa Sfoglia File",
                color = Color.Gray,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        } else {
            summaries.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1C1C))
                        .clickable { onSelectFile(item.file) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (item.coverBitmap != null) {
                        Image(
                            bitmap = item.coverBitmap.asImageBitmap(),
                            contentDescription = item.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 36.dp, height = 48.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF332020)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = item.displayTitle,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Completato: ${item.completionPercent}%",
                            color = if (item.completionPercent > 0) Color(0xFF81C784) else Color(0xFF9E9E9E),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Caricamento via Wi-Fi + Libro Demo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1B3B2B))
                    .clickable {
                        wifiUrl = if (wifiUrl == null) wifiServer.start(8080) else {
                            wifiServer.stop()
                            null
                        }
                    }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi",
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = wifiUrl ?: "Carica via Wi-Fi",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onSelectDemo() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Demo", color = Color.LightGray, fontSize = 11.sp)
            }
        }
    }
}
