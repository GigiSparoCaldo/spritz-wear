package com.spritzwear.reader

import android.content.Context
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwipeVertical
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class CrownMode {
    WPM_SPEED,   // La corona modifica la velocità in WPM
    WORD_SCRUB   // La corona fa girare le parole avanti e indietro
}

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
    val prefs = remember { context.getSharedPreferences("spritz_prefs", Context.MODE_PRIVATE) }

    var currentBook by remember { mutableStateOf(BookParser.getDemoBook()) }
    var wordIndex by remember { mutableIntStateOf(prefs.getInt("saved_index_${currentBook.id}", 0)) }
    var wpm by remember { mutableIntStateOf(prefs.getInt("saved_wpm", 300)) }
    var isPlaying by remember { mutableStateOf(false) }
    var crownMode by remember { mutableStateOf(CrownMode.WORD_SCRUB) }
    var showLibraryDialog by remember { mutableStateOf(false) }

    // Contatore per il Soft-Start (accelerazione graduale nelle prime 12 parole)
    var wordsReadInSession by remember { mutableIntStateOf(0) }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    // File Picker di sistema per caricare file .txt o .epub
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = it.lastPathSegment?.substringAfterLast('/') ?: "Libro.epub"
            val loaded = BookParser.parseUri(context, it, name)
            currentBook = loaded
            wordIndex = prefs.getInt("saved_index_${loaded.id}", 0).coerceIn(0, loaded.words.lastIndex)
            isPlaying = false
            showLibraryDialog = false
        }
    }

    // Salva automaticamente progresso e WPM
    LaunchedEffect(wordIndex, wpm, currentBook.id) {
        prefs.edit()
            .putInt("saved_index_${currentBook.id}", wordIndex)
            .putInt("saved_wpm", wpm)
            .apply()
    }

    // Loop principale del motore RSVP Spritz
    LaunchedEffect(isPlaying, wordIndex, wpm, currentBook) {
        if (isPlaying) {
            if (wordIndex >= currentBook.words.lastIndex) {
                isPlaying = false
                return@LaunchedEffect
            }
            val currentRawWord = currentBook.words[wordIndex]
            val spritz = BookParser.toSpritzWord(currentRawWord)

            // Soft-start: parte dal 60% e sale al 100% entro 12 parole
            val ramp = (0.6f + (wordsReadInSession.coerceAtMost(12) / 12f) * 0.4f)
            val delayMs = BookParser.calculateDelayMs(wpm, spritz.delayMultiplier, ramp)

            delay(delayMs)
            wordIndex = (wordIndex + 1).coerceAtMost(currentBook.words.lastIndex)
            wordsReadInSession++
        } else {
            wordsReadInSession = 0
        }
    }

    LaunchedEffect(showLibraryDialog) {
        if (!showLibraryDialog) {
            focusRequester.requestFocus()
        }
    }

    if (showLibraryDialog) {
        LibraryScreen(
            onSelectBook = { book ->
                currentBook = book
                wordIndex = prefs.getInt("saved_index_${book.id}", 0).coerceIn(0, book.words.lastIndex)
                isPlaying = false
                showLibraryDialog = false
            },
            onOpenSystemPicker = {
                filePickerLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*"))
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                rotaryAccumulator += event.verticalScrollPixels
                val threshold = 32f // Sensibilità scatto corona OnePlus Watch 3
                if (abs(rotaryAccumulator) >= threshold) {
                    val steps = (rotaryAccumulator / threshold).toInt()
                    rotaryAccumulator %= threshold

                    // Feedback aptico ad ogni scatto della corona
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                    when (crownMode) {
                        CrownMode.WORD_SCRUB -> {
                            // Modalità Scrub: la corona fa girare le parole avanti e indietro
                            isPlaying = false
                            wordIndex = (wordIndex + steps).coerceIn(0, currentBook.words.lastIndex)
                        }
                        CrownMode.WPM_SPEED -> {
                            // Modalità WPM: la corona cambia la velocità di lettura
                            wpm = (wpm + steps * 10).coerceIn(100, 1000)
                        }
                    }
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        // Bordo circolare perimetrale di avanzamento sul display rotondo
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            indicatorColor = Color(0xFFD32F2F),
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
            // Intestazione superiore: Titolo ("HEADLINE") + Statistiche WPM / Tempo rimasto
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentBook.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$wpm WPM • ${wordIndex + 1}/${currentBook.words.size} (-${remainingMinutes}m)",
                    color = Color(0xFF9E9E9E),
                    fontSize = 10.sp
                )
            }

            // Box Centrale Spritz (Mirino circolare + Guide orizzontali + Parola allineata su ORP)
            SpritzReticleStage(
                word = currentBook.words.getOrElse(wordIndex) { "" },
                onTogglePlay = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    isPlaying = !isPlaying
                }
            )

            // Barra controlli inferiore (Selettore Modalità Corona, Play/Pausa, Reset, Libreria EPUB/TXT)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Pillola interattiva per cambiare la funzione della corona
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (crownMode == CrownMode.WPM_SPEED) Color(0xFF3E1818)
                            else Color(0xFF1A2936)
                        )
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            crownMode = if (crownMode == CrownMode.WORD_SCRUB) {
                                CrownMode.WPM_SPEED
                            } else {
                                CrownMode.WORD_SCRUB
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (crownMode == CrownMode.WPM_SPEED) Icons.Default.Speed else Icons.Default.SwipeVertical,
                        contentDescription = "Modalità Corona",
                        tint = if (crownMode == CrownMode.WPM_SPEED) Color(0xFFFF5252) else Color(0xFF64B5F6),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (crownMode == CrownMode.WPM_SPEED) "CORONA: WPM" else "CORONA: PAROLE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Pulsanti rapidi: Libreria | Play/Pausa | Riavvolgi
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallCircleButton(
                        icon = Icons.Default.FolderOpen,
                        contentDesc = "Apri TXT/EPUB",
                        onClick = {
                            isPlaying = false
                            showLibraryDialog = true
                        }
                    )

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32F2F))
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
                        icon = Icons.Default.Replay,
                        contentDesc = "Inizio",
                        onClick = {
                            isPlaying = false
                            wordIndex = 0
                        }
                    )
                }
            }
        }
    }
}

/**
 * Riproduce fedelmente il frame Spritz mostrato nel video:
 * - Linea guida superiore con il mirino circolare esattamente al centro
 * - Parola allineata con la lettera ORP rossa sotto il mirino
 * - Linea guida inferiore
 */
@Composable
fun SpritzReticleStage(
    word: String,
    onTogglePlay: () -> Unit
) {
    val spritz = remember(word) { BookParser.toSpritzWord(word) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePlay() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra orizzontale superiore con mirino circolare centrale (come nel video)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            val circleRadius = 5.dp.toPx()

            // Segmento sinistro
            drawLine(
                color = Color.White,
                start = Offset(8f, centerY),
                end = Offset(centerX - circleRadius, centerY),
                strokeWidth = 1.5.dp.toPx()
            )
            // Mirino circolare centrale
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.5.dp.toPx())
            )
            // Segmento destro
            drawLine(
                color = Color.White,
                start = Offset(centerX + circleRadius, centerY),
                end = Offset(size.width - 8f, centerY),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Parola divisa in 3 blocchi per bloccare la lettera ORP rossa sotto il mirino centrale
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prefisso allineato a destra verso il centro
            Text(
                text = spritz.prefix,
                color = Color.White,
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            // Lettera ORP rossa fissa al centro esatto sotto il mirino
            Text(
                text = spritz.pivot,
                color = Color(0xFFE53935),
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            // Suffisso allineato a sinistra dopo il centro
            Text(
                text = spritz.suffix,
                color = Color.White,
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Barra orizzontale inferiore con tacca verticale di riferimento
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
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
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF262626))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
fun LibraryScreen(
    onSelectBook: (BookItem) -> Unit,
    onOpenSystemPicker: () -> Unit,
    onClose: () -> Unit
) {
    val localFiles = remember { BookParser.scanDownloadBooksFolder() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "LIBRERIA (.TXT / .EPUB)",
            color = Color(0xFFE53935),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        // Pulsante per scegliere qualsiasi file .txt o .epub tramite il file manager di Wear OS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E2A38))
                .clickable { onOpenSystemPicker() }
                .padding(vertical = 8.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Sfoglia File (.epub / .txt)",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Libro Demo integrato
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F1F1F))
                .clickable { onSelectBook(BookParser.getDemoBook()) }
                .padding(vertical = 6.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Demo: HEADLINE Spritz",
                color = Color.LightGray,
                fontSize = 11.sp
            )
        }

        // Elenco automatico dei file trovati in /sdcard/Download
        localFiles.take(2).forEach { file ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1F1F1F))
                    .clickable { onSelectBook(BookParser.parseFile(file)) }
                    .padding(vertical = 6.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = "Indietro",
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { onClose() }
        )
    }
}
