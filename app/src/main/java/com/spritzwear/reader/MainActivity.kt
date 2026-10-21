package com.spritzwear.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

enum class ActiveScreen {
    READER,
    CHAPTER_LIST,
    LIBRARY
}

class MainActivity : ComponentActivity() {

    /**
     * Forza la configurazione Android di questa Activity a 320 DPI (xhdpi, density = 2.0f),
     * annullando l'override digitale a 240 DPI impostato nel sistema.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = 320
        config.fontScale = 1.0f
        val context320 = newBase.createConfigurationContext(config)
        super.attachBaseContext(context320)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BookParser.getDedicatedFolders(this)
        setContent {
            // Blocca anche il motore Jetpack Compose esattamente a 320 DPI (320 / 160 = 2.0f)
            CompositionLocalProvider(LocalDensity provides Density(density = 2.0f, fontScale = 1.0f)) {
                MaterialTheme {
                    SpritzReaderApp()
                }
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
    var isVibrationEnabled by remember { mutableStateOf(prefs.getBoolean("saved_haptic", true)) }
    var isLoading by remember { mutableStateOf(false) }
    var activeScreen by remember { mutableStateOf(ActiveScreen.READER) }

    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    val loadFileAsync: (File) -> Unit = { file ->
        scope.launch {
            isLoading = true
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

    // Salva automaticamente posizione, totale parole e stato vibrazione
    LaunchedEffect(wordIndex, currentBook.id, isVibrationEnabled) {
        prefs.edit()
            .putInt("saved_index_${currentBook.id}", wordIndex)
            .putInt("saved_total_${currentBook.id}", currentBook.words.size)
            .putBoolean("saved_haptic", isVibrationEnabled)
            .apply()
    }

    LaunchedEffect(activeScreen, isLoading) {
        if (activeScreen == ActiveScreen.READER && !isLoading) {
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

    when (activeScreen) {
        ActiveScreen.CHAPTER_LIST -> {
            ChapterListScreen(
                book = currentBook,
                currentWordIndex = wordIndex,
                isVibrationEnabled = isVibrationEnabled,
                onSelectChapter = { chapter ->
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

    // Calcola il capitolo attuale e la percentuale di completamento del singolo capitolo
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

                    if (isVibrationEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    wordIndex = (wordIndex + steps).coerceIn(0, currentBook.words.lastIndex)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // INTESTAZIONE CLICCABILE: Toccando qui si apre la lista dei capitoli
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141414))
                    .clickable {
                        if (isVibrationEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                        activeScreen = ActiveScreen.CHAPTER_LIST
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentChapter.title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Scegli Capitolo",
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = "Capitolo al $chapterPercent% • (${currentChapterIndex + 1}/${currentBook.chapters.size})",
                    color = Color(0xFF9E9E9E),
                    fontSize = 9.5.sp,
                    maxLines = 1
                )
            }

            // MIRINO SPRITZ CENTRALE CON PAROLA E CONTESTO SOTTOSTANTE
            SpritzReticleStage(
                words = currentBook.words,
                wordIndex = wordIndex
            )

            // BARRA CONTROLLI INFERIORE (Libreria | Cap. Prec | Cap. Succ | Vibrazione Corona)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                SmallCircleButton(
                    icon = Icons.Default.FolderOpen,
                    contentDesc = "Libreria EPUB/TXT",
                    onClick = { activeScreen = ActiveScreen.LIBRARY }
                )

                SmallCircleButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDesc = "Capitolo Precedente",
                    onClick = {
                        val prevIdx = if (wordIndex > currentChapter.startWordIndex + 3) {
                            currentChapter.startWordIndex
                        } else {
                            currentBook.chapters.getOrNull(currentChapterIndex - 1)?.startWordIndex ?: 0
                        }
                        wordIndex = prevIdx
                    }
                )

                SmallCircleButton(
                    icon = Icons.Default.SkipNext,
                    contentDesc = "Capitolo Successivo",
                    onClick = {
                        val nextIdx = currentBook.chapters.getOrNull(currentChapterIndex + 1)?.startWordIndex
                            ?: currentBook.words.lastIndex
                        wordIndex = nextIdx
                    }
                )

                Box(
                    modifier = Modifier
                        .size(28.dp)
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
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SpritzReticleStage(
    words: List<String>,
    wordIndex: Int
) {
    val word = words.getOrElse(wordIndex) { "" }
    val spritz = remember(word) { BookParser.toSpritzWord(word) }
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Barra superiore con mirino circolare centrale[cite: 1]
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(13.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            val circleRadius = 4.5.dp.toPx()

            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(centerX - circleRadius, centerY),
                strokeWidth = 1.4.dp.toPx()
            )
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.4.dp.toPx())
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX + circleRadius, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.4.dp.toPx()
            )
        }

        // 2. Parola su Canvas a 320 DPI nativi con centratura ORP e anti-taglio[cite: 1]
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
        ) {
            if (word.isEmpty()) return@Canvas

            val safePadding = 8.dp.toPx()
            val availableWidth = size.width - (safePadding * 2f)
            val centerX = size.width / 2f

            var fontSizeSp = when {
                word.length <= 8 -> 24f
                word.length in 9..11 -> 21f
                word.length in 12..14 -> 18f
                word.length in 15..18 -> 15.5f
                else -> 13.5f
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

        // 3. Barra inferiore con tacca rossa[cite: 1]
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(9.dp)
        ) {
            val centerY = size.height / 2f
            val centerX = size.width / 2f
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.4.dp.toPx()
            )
            drawLine(
                color = Color(0xFFE53935),
                start = Offset(centerX, centerY - 3.5.dp.toPx()),
                end = Offset(centerX, centerY),
                strokeWidth = 1.4.dp.toPx()
            )
        }

        // Anteprima del contesto della frase intorno alla parola attiva
        if (words.isNotEmpty()) {
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
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp, start = 14.dp, end = 14.dp)
            )
        }
    }
}

/**
 * Schermata di selezione dei Capitoli quando si clicca sul titolo in alto.
 */
@Composable
fun ChapterListScreen(
    book: BookItem,
    currentWordIndex: Int,
    isVibrationEnabled: Boolean,
    onSelectChapter: (ChapterInfo) -> Unit,
    onClose: () -> Unit
) {
    val view = LocalView.current
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                scope.launch {
                    scrollState.scrollBy(event.verticalScrollPixels)
                }
                if (isVibrationEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "CAPITOLI (${book.chapters.size})",
            color = Color(0xFFE53935),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

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
                    .background(if (isCurrent) Color(0xFF3E1818) else Color(0xFF1E1E1E))
                    .clickable { onSelectChapter(chapter) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${idx + 1}. ${chapter.title}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$chapPercent%",
                    color = if (isCurrent) Color(0xFFFF8A80) else Color(0xFF9E9E9E),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2C2C2C))
                .clickable { onClose() }
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text("Chiudi", color = Color.White, fontSize = 10.sp)
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

/**
 * Schermata Libreria con miniatura Copertina EPUB e Percentuale di Completamento affiancata.
 */
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
    val focusRequester = remember { FocusRequester() }

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
        focusRequester.requestFocus()
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
            .onRotaryScrollEvent { event ->
                scope.launch { scrollState.scrollBy(event.verticalScrollPixels) }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "LIBRERIA SPRITZBOOKS",
            color = Color(0xFFE53935),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        // Pulsante Scansione / Permesso /sdcard/SpritzBooks
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
                    refreshLibrary()
                }
                .padding(vertical = 6.dp, horizontal = 8.dp),
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
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (hasAllFilesPermission) "Scansiona /SpritzBooks (${summaries.size})"
                    else "Abilita Permesso /SpritzBooks",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Lista dei Libri con Copertina e Percentuale di Completamento
        if (isScanning) {
            Text("Lettura copertine...", color = Color.Gray, fontSize = 9.5.sp)
        } else if (summaries.isEmpty()) {
            Text(
                text = "Copia i tuoi .epub o .txt in /sdcard/SpritzBooks",
                color = Color.Gray,
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        } else {
            summaries.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1C1C1C))
                        .clickable { onSelectFile(item.file) }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copertina Libro EPUB (o icona libro se .txt / privo di copertina)
                    if (item.coverBitmap != null) {
                        Image(
                            bitmap = item.coverBitmap.asImageBitmap(),
                            contentDescription = item.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 28.dp, height = 38.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 28.dp, height = 38.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF332020)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Titolo + Percentuale di completamento
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = item.displayTitle,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Completato: ${item.completionPercent}%",
                            color = if (item.completionPercent > 0) Color(0xFF81C784) else Color(0xFF9E9E9E),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
                    text = wifiUrl ?: "Carica via Wi-Fi",
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
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("Sfoglia", color = Color.White, fontSize = 9.5.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { onSelectDemo() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("Demo", color = Color.LightGray, fontSize = 9.5.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3E1818))
                    .clickable { onClose() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text("Chiudi", color = Color.White, fontSize = 9.5.sp)
            }
        }
    }
}
