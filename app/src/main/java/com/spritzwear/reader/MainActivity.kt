package com.spritzwear.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DoNotDisturbOn
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
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

enum class CrownControlSetting {
    AUTO_SMART,  // Play -> cambia WPM | Pausa -> scorre le parole
    ALWAYS_WPM,  // La corona cambia sempre i WPM
    ALWAYS_SCRUB // La corona scorre sempre le parole
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crea subito le cartelle /sdcard/SpritzBooks all'avvio
        BookParser.getDedicatedFolders(this)
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
    var isVibrationEnabled by remember { mutableStateOf(prefs.getBoolean("saved_haptic", true)) }
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var crownSetting by remember { mutableStateOf(CrownControlSetting.AUTO_SMART) }
    var showLibraryDialog by remember { mutableStateOf(false) }

    var wordsReadInSession by remember { mutableIntStateOf(0) }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    val activeCrownIsWpm = when (crownSetting) {
        CrownControlSetting.AUTO_SMART -> isPlaying
        CrownControlSetting.ALWAYS_WPM -> true
        CrownControlSetting.ALWAYS_SCRUB -> false
    }

    val loadFileAsync: (File) -> Unit = { file ->
        scope.launch {
            isLoading = true
            isPlaying = false
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

    LaunchedEffect(wordIndex, wpm, currentBook.id, isVibrationEnabled) {
        prefs.edit()
            .putInt("saved_index_${currentBook.id}", wordIndex)
            .putInt("saved_wpm", wpm)
            .putBoolean("saved_haptic", isVibrationEnabled)
            .apply()
    }

    // Loop di lettura rapida RSVP Spritz
    LaunchedEffect(isPlaying, wordIndex, wpm, currentBook) {
        if (isPlaying) {
            if (wordIndex >= currentBook.words.lastIndex) {
                isPlaying = false
                return@LaunchedEffect
            }
            val currentRawWord = currentBook.words[wordIndex]
            val spritz = BookParser.toSpritzWord(currentRawWord)
            val ramp = (0.6f + (wordsReadInSession.coerceAtMost(12) / 12f) * 0.4f)
            val delayMs = BookParser.calculateDelayMs(wpm, spritz.delayMultiplier, ramp)

            delay(delayMs)
            wordIndex = (wordIndex + 1).coerceAtMost(currentBook.words.lastIndex)
            wordsReadInSession++
        } else {
            wordsReadInSession = 0
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
                Spacer(modifier = Modifier.height(6.dp))
                Text("Caricamento...", color = Color.White, fontSize = 11.sp)
            }
        }
        return
    }

    if (showLibraryDialog) {
        LibraryScreen(
            onSelectFile = { file -> loadFileAsync(file) },
            onSelectDemo = {
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
                val threshold = 28f
                if (abs(rotaryAccumulator) >= threshold) {
                    val steps = (rotaryAccumulator / threshold).toInt()
                    rotaryAccumulator %= threshold

                    // Vibrazione Corona attivabile/disattivabile dall'utente
                    if (isVibrationEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    if (activeCrownIsWpm) {
                        wpm = (wpm + steps * 10).coerceIn(100, 1000)
                    } else {
                        isPlaying = false
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
            modifier = Modifier.fillMaxSize().padding(2.dp),
            indicatorColor = Color(0xFFD32F2F),
            trackColor = Color(0xFF1A1A1A),
            strokeWidth = 2.5.dp
        )

        // Layout calibrato per 240 DPI: massimizza la larghezza centrale sul quadrante rotondo
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Intestazione compatta
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = currentBook.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Cap.$currentChapterNum • $wpm WPM • -${remainingMinutes}m",
                    color = Color(0xFF9E9E9E),
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }

            // Mirino Spritz con Centratura Dinamica Anti-Taglio su Canvas
            SpritzReticleStage(
                words = currentBook.words,
                wordIndex = wordIndex,
                isPlaying = isPlaying,
                onTogglePlay = {
                    if (isVibrationEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    isPlaying = !isPlaying
                }
            )

            // Barra inferiore con Toggle Modalità Corona + 5 controlli (incluso Vibrazione On/Off)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (activeCrownIsWpm) Color(0xFF3E1818) else Color(0xFF1A2936)
                        )
                        .clickable {
                            if (isVibrationEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                            crownSetting = when (crownSetting) {
                                CrownControlSetting.AUTO_SMART -> CrownControlSetting.ALWAYS_WPM
                                CrownControlSetting.ALWAYS_WPM -> CrownControlSetting.ALWAYS_SCRUB
                                CrownControlSetting.ALWAYS_SCRUB -> CrownControlSetting.AUTO_SMART
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = if (activeCrownIsWpm) Icons.Default.Speed else Icons.Default.SwipeVertical,
                        contentDescription = "Modalità Corona",
                        tint = if (activeCrownIsWpm) Color(0xFFFF5252) else Color(0xFF64B5F6),
                        modifier = Modifier.size(10.dp)
                    )
                    val modeLabel = when (crownSetting) {
                        CrownControlSetting.AUTO_SMART -> if (isPlaying) "AUTO: WPM" else "AUTO: PAROLE"
                        CrownControlSetting.ALWAYS_WPM -> "FISSO: WPM"
                        CrownControlSetting.ALWAYS_SCRUB -> "FISSO: PAROLE"
                    }
                    Text(
                        text = modeLabel,
                        color = Color.White,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Libreria (Cartella SpritzBooks)
                    SmallCircleButton(
                        icon = Icons.Default.FolderOpen,
                        contentDesc = "Cartella SpritzBooks",
                        onClick = {
                            isPlaying = false
                            showLibraryDialog = true
                        }
                    )

                    // 2. Capitolo Precedente
                    SmallCircleButton(
                        icon = Icons.Default.SkipPrevious,
                        contentDesc = "Capitolo Precedente",
                        onClick = {
                            isPlaying = false
                            val prevChap = currentBook.chapterIndices.lastOrNull { it < wordIndex - 5 } ?: 0
                            wordIndex = prevChap
                        }
                    )

                    // 3. Play / Pausa
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32F2F))
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
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 4. Capitolo Successivo
                    SmallCircleButton(
                        icon = Icons.Default.SkipNext,
                        contentDesc = "Capitolo Successivo",
                        onClick = {
                            isPlaying = false
                            val nextChap = currentBook.chapterIndices.firstOrNull { it > wordIndex }
                                ?: currentBook.words.lastIndex
                            wordIndex = nextChap
                        }
                    )

                    // 5. Toggle Vibrazione Corona On / Off
                    Box(
                        modifier = Modifier
                            .size(24.dp)
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
                            contentDescription = "Vibrazione Corona On/Off",
                            tint = if (isVibrationEnabled) Color(0xFF81C784) else Color(0xFF757575),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Motore di rendering Spritz su Canvas calibrato per 240 DPI:
 * - Disegna l'intera parola su un unico livello anziché in 3 colonne separate.
 * - Posiziona la lettera rossa (ORP) esattamente sotto il mirino circolare.
 * - Se una parola è molto lunga e il suffisso rischia di uscire a destra o a sinistra,
 *   riduce automaticamente la dimensione in sp e applica uno shift morbido verso il centro
 *   così il 100% delle lettere è sempre visibile e leggibile.
 */
@Composable
fun SpritzReticleStage(
    words: List<String>,
    wordIndex: Int,
    isPlaying: Boolean,
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
                .height(12.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            val circleRadius = 4.dp.toPx()

            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(centerX - circleRadius, centerY),
                strokeWidth = 1.25.dp.toPx()
            )
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.25.dp.toPx())
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX + circleRadius, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.25.dp.toPx()
            )
        }

        // 2. Canvas centrale della parola con auto-fit e allineamento ORP
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            if (word.isEmpty()) return@Canvas

            val safePadding = 6.dp.toPx()
            val availableWidth = size.width - (safePadding * 2f)
            val centerX = size.width / 2f

            // Scala base per 240 DPI
            var fontSizeSp = when {
                word.length <= 8 -> 22f
                word.length in 9..11 -> 19f
                word.length in 12..14 -> 16.5f
                word.length in 15..18 -> 14.5f
                else -> 12.5f
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

            // Se la parola intera è ancora più larga dello schermo disponibile, riduce il font finché entra
            while (layoutResult.size.width > availableWidth && fontSizeSp > 10f) {
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

            // Calcola la posizione esatta del centro della lettera rossa (ORP)
            val pivotCharIndex = spritz.prefix.length
            val pivotBox = layoutResult.getBoundingBox(pivotCharIndex)
            val pivotCenterInText = (pivotBox.left + pivotBox.right) / 2f

            // Posizione ideale: il centro della lettera rossa cade sotto il mirino (centerX)
            var drawX = centerX - pivotCenterInText

            // Protezione anti-taglio: se una parola lunghissima sporge a destra o a sinistra,
            // la fa scorrere quel tanto che basta per non tagliare nessuna lettera
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
                .height(8.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.25.dp.toPx()
            )
            drawLine(
                color = Color(0xFFE53935),
                start = Offset(centerX, centerY - 3.dp.toPx()),
                end = Offset(centerX, centerY),
                strokeWidth = 1.25.dp.toPx()
            )
        }

        // Contesto della frase visibile in Pausa
        if (!isPlaying && words.isNotEmpty()) {
            val startCtx = (wordIndex - 3).coerceAtLeast(0)
            val endCtx = (wordIndex + 3).coerceAtMost(words.lastIndex)
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
                fontSize = 8.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp, start = 14.dp, end = 14.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
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
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFF262626))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = Color.White,
            modifier = Modifier.size(13.dp)
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
    var savedFiles by remember { mutableStateOf(BookParser.scanBooksFolders(context)) }
    var wifiUrl by remember { mutableStateOf<String?>(null) }
    var hasAllFilesPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else true
        )
    }

    val wifiServer = remember {
        WifiBookServer(context) { uploadedFile ->
            savedFiles = BookParser.scanBooksFolders(context)
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "CARTELLA: /sdcard/SpritzBooks",
            color = Color(0xFFE53935),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        // Pulsante per scansionare subito la cartella /sdcard/SpritzBooks
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
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
                    savedFiles = BookParser.scanBooksFolders(context)
                }
                .padding(vertical = 5.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Scansiona",
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (hasAllFilesPermission) "Scansiona SpritzBooks (${savedFiles.size})"
                    else "Abilita Lettura /SpritzBooks",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Elenco di tutti i file .epub / .txt trovati nella cartella SpritzBooks
        if (savedFiles.isEmpty()) {
            Text(
                text = "Nessun file in /sdcard/SpritzBooks",
                color = Color.Gray,
                fontSize = 8.5.sp,
                textAlign = TextAlign.Center
            )
        } else {
            savedFiles.forEach { file ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1F1F1F))
                        .clickable { onSelectFile(file) }
                        .padding(vertical = 5.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = file.name,
                        color = Color.White,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Caricamento diretto in SpritzBooks via Wi-Fi
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1B3B2B))
                .clickable {
                    wifiUrl = if (wifiUrl == null) wifiServer.start(8080) else {
                        wifiServer.stop()
                        null
                    }
                }
                .padding(vertical = 5.dp, horizontal = 8.dp),
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
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = wifiUrl ?: "Invia via Wi-Fi in SpritzBooks",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E2A38))
                    .clickable { onOpenSystemPicker() }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text("Sfoglia", color = Color.White, fontSize = 9.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onSelectDemo() }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text("Demo", color = Color.LightGray, fontSize = 9.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3E1818))
                    .clickable { onClose() }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text("Chiudi", color = Color.White, fontSize = 9.sp)
            }
        }
    }
}
