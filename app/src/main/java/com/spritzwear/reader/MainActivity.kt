package com.spritzwear.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class CrownControlSetting {
    AUTO_SMART,  // Play -> cambia WPM | Pausa -> scorre le parole
    ALWAYS_WPM,  // La corona cambia sempre i WPM
    ALWAYS_SCRUB // La corona scorre sempre le parole
}

private data class WordSpan(
    val globalIndex: Int,
    val startChar: Int,
    val endChar: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SpritzReaderApp()
            }
        }
    }
}

@Composable
fun SpritzReaderApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("spritz_prefs", Context.MODE_PRIVATE) }

    var currentBook by remember { mutableStateOf(BookParser.getDemoBook()) }
    var wordIndex by remember { mutableIntStateOf(prefs.getInt("saved_index_${currentBook.id}", 0)) }
    var wpm by remember { mutableIntStateOf(prefs.getInt("saved_wpm", 300)) }
    var isPlaying by remember { mutableStateOf(false) }
    var isTtsEnabled by remember { mutableStateOf(false) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var crownSetting by remember { mutableStateOf(CrownControlSetting.AUTO_SMART) }
    var showLibraryDialog by remember { mutableStateOf(false) }

    var wordsReadInSession by remember { mutableIntStateOf(0) }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    // Riferimento all'istanza TextToSpeech e mappa della frase attualmente in pronuncia
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    val currentChunkSpans = remember { mutableStateListOf<WordSpan>() }
    var currentUtteranceDone by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var rangeCallbackTriggered by remember { mutableStateOf(false) }

    // Inizializzazione TextToSpeech di sistema
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.ITALIAN
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    // Chiamato da Wear OS mentre pronuncia ogni singola parola della frase
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

    // Imposta automaticamente la lingua del TTS (Italiano o Inglese) quando cambia il libro
    LaunchedEffect(currentBook, isTtsReady) {
        if (isTtsReady) {
            val sampleText = currentBook.words.take(40).joinToString(" ").lowercase()
            val englishMarkers = listOf(" the ", " and ", " with ", " delivers ", " flashes ")
            val isEnglish = englishMarkers.count { sampleText.contains(it) } >= 2
            ttsEngine?.language = if (isEnglish) Locale.US else Locale.ITALIAN
        }
    }

    val activeCrownIsWpm = when (crownSetting) {
        CrownControlSetting.AUTO_SMART -> isPlaying
        CrownControlSetting.ALWAYS_WPM -> true
        CrownControlSetting.ALWAYS_SCRUB -> false
    }

    val loadFileAsync: (File) -> Unit = { file ->
        scope.launch {
            isLoading = true
            isPlaying = false
            ttsEngine?.stop()
            val loaded = withContext(Dispatchers.IO) { BookParser.parseFile(file) }
            currentBook = loaded
            wordIndex = prefs.getInt("saved_index_${loaded.id}", 0).coerceIn(0, loaded.words.lastIndex)
            isLoading = false
            showLibraryDialog = false
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
                showLibraryDialog = false
            }
        }
    }

    LaunchedEffect(wordIndex, wpm, currentBook.id) {
        prefs.edit()
            .putInt("saved_index_${currentBook.id}", wordIndex)
            .putInt("saved_wpm", wpm)
            .apply()
    }

    // Motore Duale: Visivo Puro (RSVP) oppure Ibrido Voce TTS + Spritz
    LaunchedEffect(isPlaying, isTtsEnabled, currentBook) {
        if (!isPlaying) {
            ttsEngine?.stop()
            currentUtteranceDone?.complete(Unit)
            wordsReadInSession = 0
            return@LaunchedEffect
        }

        if (isTtsEnabled && isTtsReady && ttsEngine != null) {
            // MODALITÀ IBRIDA TTS + SPRITZ
            while (isPlaying && wordIndex <= currentBook.words.lastIndex) {
                val startIdx = wordIndex
                val spans = mutableListOf<WordSpan>()
                val sb = StringBuilder()
                var idx = startIdx

                // Costruisce una frase naturale (fino a 18 parole o fino a un segno di fine frase)
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

                // Converte i WPM scelti con la corona in velocità vocale (180 WPM = 1.0x)
                val speechRate = (wpm / 180f).coerceIn(0.5f, 3.0f)
                ttsEngine?.setSpeechRate(speechRate)

                val doneDeferred = CompletableDeferred<Unit>()
                currentUtteranceDone = doneDeferred

                ttsEngine?.speak(
                    sb.toString().trim(),
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "SPRITZ_UTTERANCE_$startIdx"
                )

                // Avanzamento sincronizzato: usa onRangeStart se supportato dalla voce,
                // altrimenti avanza con il timer calcolato fino al completamento della frase
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

                // Attende che il motore vocale finisca la frase prima di passare alla successiva
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
            // MODALITÀ VISIVA PURA ULTRA-VELOCE (100 - 1000 WPM)
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

    LaunchedEffect(showLibraryDialog, isLoading) {
        if (!showLibraryDialog && !isLoading) {
            focusRequester.requestFocus()
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(indicatorColor = Color(0xFFE53935))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Apertura libro...", color = Color.White, fontSize = 12.sp)
            }
        }
        return
    }

    if (showLibraryDialog) {
        LibraryScreen(
            onSelectFile = { file -> loadFileAsync(file) },
            onSelectDemo = {
                ttsEngine?.stop()
                currentBook = BookParser.getDemoBook()
                wordIndex = 0
                isPlaying = false
                showLibraryDialog = false
            },
            onOpenSystemPicker = {
                try {
                    filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*"))
                } catch (_: Exception) {
                }
            },
            onClose = { showLibraryDialog = false }
        )
        return
    }

    val progress = if (currentBook.words.size > 1) {
        wordIndex.toFloat() / (currentBook.words.size - 1).toFloat()
    } else 1f

    val remainingWords = (currentBook.words.size - 1 - wordIndex).coerceAtLeast(0)
    val remainingMinutes = (remainingWords.toFloat() / wpm.toFloat()).toInt()
    val currentChapterNum = currentBook.chapterIndices.count { it <= wordIndex }.coerceAtLeast(1)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                rotaryAccumulator += event.verticalScrollPixels
                val threshold = 30f
                if (abs(rotaryAccumulator) >= threshold) {
                    val steps = (rotaryAccumulator / threshold).toInt()
                    rotaryAccumulator %= threshold
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                    if (activeCrownIsWpm) {
                        wpm = (wpm + steps * 10).coerceIn(100, 1000)
                        if (isTtsEnabled && isPlaying) {
                            ttsEngine?.setSpeechRate((wpm / 180f).coerceIn(0.5f, 3.0f))
                        }
                    } else {
                        isPlaying = false
                        ttsEngine?.stop()
                        wordIndex = (wordIndex + steps).coerceIn(0, currentBook.words.lastIndex)
                    }
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize().padding(3.dp),
            indicatorColor = if (isTtsEnabled) Color(0xFF4CAF50) else Color(0xFFD32F2F),
            trackColor = Color(0xFF1E1E1E),
            strokeWidth = 3.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Intestazione: Titolo + Capitolo, WPM (o Velocità Voce) e Minuti rimasti
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentBook.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val speedInfo = if (isTtsEnabled) {
                    val rate = String.format(Locale.US, "%.1fx", (wpm / 180f).coerceIn(0.5f, 3.0f))
                    "VOCE $rate ($wpm WPM)"
                } else {
                    "$wpm WPM"
                }
                Text(
                    text = "Cap.$currentChapterNum • $speedInfo • -${remainingMinutes}m",
                    color = if (isTtsEnabled) Color(0xFF81C784) else Color(0xFF9E9E9E),
                    fontSize = 10.sp
                )
            }

            // Mirino Spritz Centrale fedele al layout del video
            SpritzReticleStage(
                words = currentBook.words,
                wordIndex = wordIndex,
                isPlaying = isPlaying,
                onTogglePlay = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    isPlaying = !isPlaying
                }
            )

            // Controlli Inferiori: Badge Corona + Libreria + Capitoli + Play/Pausa + Toggle Voce TTS
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (activeCrownIsWpm) Color(0xFF3E1818) else Color(0xFF1A2936)
                        )
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            crownSetting = when (crownSetting) {
                                CrownControlSetting.AUTO_SMART -> CrownControlSetting.ALWAYS_WPM
                                CrownControlSetting.ALWAYS_WPM -> CrownControlSetting.ALWAYS_SCRUB
                                CrownControlSetting.ALWAYS_SCRUB -> CrownControlSetting.AUTO_SMART
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (activeCrownIsWpm) Icons.Default.Speed else Icons.Default.SwipeVertical,
                        contentDescription = "Modalità Corona",
                        tint = if (activeCrownIsWpm) Color(0xFFFF5252) else Color(0xFF64B5F6),
                        modifier = Modifier.size(11.dp)
                    )
                    val modeLabel = when (crownSetting) {
                        CrownControlSetting.AUTO_SMART -> if (isPlaying) "AUTO: WPM" else "AUTO: PAROLE"
                        CrownControlSetting.ALWAYS_WPM -> "FISSO: WPM"
                        CrownControlSetting.ALWAYS_SCRUB -> "FISSO: PAROLE"
                    }
                    Text(
                        text = modeLabel,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallCircleButton(
                        icon = Icons.Default.FolderOpen,
                        contentDesc = "Libreria",
                        onClick = {
                            isPlaying = false
                            ttsEngine?.stop()
                            showLibraryDialog = true
                        }
                    )

                    SmallCircleButton(
                        icon = Icons.Default.SkipPrevious,
                        contentDesc = "Capitolo Precedente",
                        onClick = {
                            isPlaying = false
                            ttsEngine?.stop()
                            val prevChap = currentBook.chapterIndices.lastOrNull { it < wordIndex - 5 } ?: 0
                            wordIndex = prevChap
                        }
                    )

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isTtsEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                if (wordIndex >= currentBook.words.lastIndex) wordIndex = 0
                                isPlaying = !isPlaying
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pausa",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    SmallCircleButton(
                        icon = Icons.Default.SkipNext,
                        contentDesc = "Capitolo Successivo",
                        onClick = {
                            isPlaying = false
                            ttsEngine?.stop()
                            val nextChap = currentBook.chapterIndices.firstOrNull { it > wordIndex }
                                ?: currentBook.words.lastIndex
                            wordIndex = nextChap
                        }
                    )

                    // Pulsante Toggle TTS (Passa da Solo Visivo a Ibrido Voce + Spritz)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isTtsEnabled) Color(0xFF1B5E20) else Color(0xFF262626))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                isTtsEnabled = !isTtsEnabled
                                if (!isTtsEnabled) {
                                    ttsEngine?.stop()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isTtsEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Attiva/Disattiva Voce TTS",
                            tint = if (isTtsEnabled) Color(0xFF81C784) else Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpritzReticleStage(
    words: List<String>,
    wordIndex: Int,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit
) {
    val word = words.getOrElse(wordIndex) { "" }
    val spritz = remember(word) { BookParser.toSpritzWord(word) }

    val dynamicFontSize = when {
        word.length <= 9 -> 24.sp
        word.length in 10..13 -> 20.sp
        word.length in 14..17 -> 17.sp
        else -> 14.sp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePlay() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra superiore con mirino circolare centrale
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            val circleRadius = 5.dp.toPx()

            drawLine(
                color = Color.White,
                start = Offset(8f, centerY),
                end = Offset(centerX - circleRadius, centerY),
                strokeWidth = 1.5.dp.toPx()
            )
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX + circleRadius, centerY),
                end = Offset(size.width - 8f, centerY),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Parola allineata con ORP rosso fisso sotto il mirino
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = spritz.prefix,
                color = Color.White,
                fontSize = dynamicFontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = spritz.pivot,
                color = Color(0xFFE53935),
                fontSize = dynamicFontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                text = spritz.suffix,
                color = Color.White,
                fontSize = dynamicFontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Barra inferiore
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            drawLine(
                color = Color.White,
                start = Offset(8f, centerY),
                end = Offset(size.width - 8f, centerY),
                strokeWidth = 1.5.dp.toPx()
            )
            drawLine(
                color = Color(0xFFE53935),
                start = Offset(centerX, centerY - 3.dp.toPx()),
                end = Offset(centerX, centerY),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // Contesto della frase visibile quando in Pausa
        if (!isPlaying && words.isNotEmpty()) {
            val startCtx = (wordIndex - 4).coerceAtLeast(0)
            val endCtx = (wordIndex + 4).coerceAtMost(words.lastIndex)
            val annotatedContext = buildAnnotatedString {
                for (i in startCtx..endCtx) {
                    if (i == wordIndex) {
                        withStyle(SpanStyle(color = Color(0xFFE53935), fontWeight = FontWeight.Bold)) {
                            append("${words[i]} ")
                        }
                    } else {
                        withStyle(SpanStyle(color = Color(0xFF757575))) {
                            append("${words[i]} ")
                        }
                    }
                }
            }
            Text(
                text = annotatedContext,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, start = 12.dp, end = 12.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(13.dp))
        }
    }
}

@Composable
fun SmallCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0xFF262626))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
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
    var savedFiles by remember { mutableStateOf(BookParser.listSavedBooks(context)) }
    var wifiUrl by remember { mutableStateOf<String?>(null) }

    val wifiServer = remember {
        WifiBookServer(context) { uploadedFile ->
            savedFiles = BookParser.listSavedBooks(context)
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "LIBRERIA (.EPUB / .TXT)",
            color = Color(0xFFE53935),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1B3B2B))
                .clickable {
                    wifiUrl = if (wifiUrl == null) wifiServer.start(8080) else {
                        wifiServer.stop()
                        null
                    }
                }
                .padding(vertical = 6.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "Wi-Fi",
                    tint = Color(0xFF81C784),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = wifiUrl ?: "Carica via Wi-Fi (PC/Tel)",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        savedFiles.take(2).forEach { file ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1F1F1F))
                    .clickable { onSelectFile(file) }
                    .padding(vertical = 6.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E2A38))
                    .clickable { onOpenSystemPicker() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("File Picker", color = Color.White, fontSize = 10.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onSelectDemo() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("Demo", color = Color.LightGray, fontSize = 10.sp)
            }
        }

        Text(
            text = "Chiudi",
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.clickable { onClose() }
        )
    }
}
