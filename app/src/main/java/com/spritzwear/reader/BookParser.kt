package com.spritzwear.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

data class SpritzWord(
    val prefix: String,
    val pivot: String,
    val suffix: String
)

data class ChapterInfo(
    val title: String,
    val startWordIndex: Int,
    val endWordIndex: Int
)

data class BookItem(
    val id: String,
    val title: String,
    val words: List<String>,
    val chapters: List<ChapterInfo>
)

data class BookSummary(
    val file: File,
    val id: String,
    val displayTitle: String,
    val coverBitmap: Bitmap?,
    val completionPercent: Int
)

object BookParser {

    fun toSpritzWord(rawWord: String): SpritzWord {
        val word = rawWord.trim()
        if (word.isEmpty()) return SpritzWord("", "", "")

        val cleanLength = word.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'', ')', ']').length
        val pivotIndex = when {
            cleanLength <= 1 -> 0
            cleanLength in 2..5 -> 1
            cleanLength in 6..9 -> 2
            cleanLength in 10..13 -> 3
            cleanLength in 14..17 -> 4
            else -> 5
        }.coerceAtMost(word.length - 1)

        val prefix = word.substring(0, pivotIndex)
        val pivot = word.substring(pivotIndex, pivotIndex + 1)
        val suffix = if (pivotIndex + 1 < word.length) word.substring(pivotIndex + 1) else ""

        return SpritzWord(prefix, pivot, suffix)
    }

    fun getDedicatedFolders(context: Context): List<File> {
        val folders = mutableListOf<File>()

        val rootSpritz = File(Environment.getExternalStorageDirectory(), "SpritzBooks")
        runCatching { if (!rootSpritz.exists()) rootSpritz.mkdirs() }
        folders.add(rootSpritz)

        val downloadSpritz = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SpritzBooks"
        )
        runCatching { if (!downloadSpritz.exists()) downloadSpritz.mkdirs() }
        folders.add(downloadSpritz)

        context.externalMediaDirs.firstOrNull()?.let { mediaDir ->
            val mediaBooks = File(mediaDir, "Books")
            runCatching { if (!mediaBooks.exists()) mediaBooks.mkdirs() }
            folders.add(mediaBooks)
        }

        val internalBooks = File(context.filesDir, "SpritzBooks")
        runCatching { if (!internalBooks.exists()) internalBooks.mkdirs() }
        folders.add(internalBooks)

        return folders
    }

    fun scanBooksFolders(context: Context): List<File> {
        val dirsToScan = getDedicatedFolders(context) + listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            context.filesDir
        )

        return dirsToScan
            .filterNotNull()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".txt", true) || file.name.endsWith(".epub", true))
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Costruisce i metadati della Libreria (Copertina ridimensionata + Percentuale di completamento).
     */
    fun getBookSummaries(context: Context): List<BookSummary> {
        val prefs = context.getSharedPreferences("spritz_prefs", Context.MODE_PRIVATE)
        val files = scanBooksFolders(context)

        return files.map { file ->
            val id = file.name
            val savedIdx = prefs.getInt("saved_index_$id", 0)
            var totalWords = prefs.getInt("saved_total_$id", 0)

            // Se il libro non è mai stato aperto, esegue un pre-calcolo rapido del totale parole
            if (totalWords <= 0) {
                runCatching {
                    val parsed = parseFile(context, file)
                    totalWords = parsed.words.size
                    prefs.edit().putInt("saved_total_$id", totalWords).apply()
                }
            }

            val percent = if (totalWords > 1) {
                ((savedIdx.toFloat() / (totalWords - 1).toFloat()) * 100f).toInt().coerceIn(0, 100)
            } else 0

            val cover = if (file.name.endsWith(".epub", true)) {
                extractEpubCover(context, file)
            } else null

            BookSummary(
                file = file,
                id = id,
                displayTitle = file.name.substringBeforeLast('.'),
                coverBitmap = cover,
                completionPercent = percent
            )
        }
    }

    /**
     * Estrae l'immagine di copertina dall'EPUB e la salva in cache per non rallentare la lista.
     */
    private fun extractEpubCover(context: Context, epubFile: File): Bitmap? {
        val cacheDir = File(context.cacheDir, "covers").apply { mkdirs() }
        val cachedCover = File(cacheDir, "${epubFile.name}.png")
        if (cachedCover.exists()) {
            return BitmapFactory.decodeFile(cachedCover.absolutePath)
        }

        var bestBytes: ByteArray? = null
        runCatching {
            ZipInputStream(epubFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase()
                    if (!entry.isDirectory && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))) {
                        val bytes = zip.readBytes()
                        if (lower.contains("cover") || lower.contains("copertina") || lower.contains("front")) {
                            bestBytes = bytes
                            break
                        } else if (bestBytes == null && bytes.size in 1_500..2_500_000) {
                            bestBytes = bytes
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val data = bestBytes ?: return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 96 || opts.outHeight / sample > 96) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
            if (bmp != null) {
                cachedCover.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            }
            bmp
        }.getOrNull()
    }

    fun importUriToInternalStorage(context: Context, uri: Uri, displayName: String): BookItem {
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetDir = File(context.filesDir, "SpritzBooks").apply { mkdirs() }
        val destFile = File(targetDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return parseFile(context, destFile)
    }

    fun parseFile(context: Context, file: File): BookItem {
        if (!file.exists()) return getDemoBook()
        val item = file.inputStream().use { parseStream(it, file.name) }
        context.getSharedPreferences("spritz_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("saved_total_${item.id}", item.words.size)
            .apply()
        return item
    }

    private fun parseStream(inputStream: InputStream, fileName: String): BookItem {
        return if (fileName.lowercase().endsWith(".epub")) {
            parseEpubStream(inputStream, fileName)
        } else {
            val text = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            val words = tokenize(text).ifEmpty { listOf("File", "vuoto.") }
            val step = 1000
            val chapters = mutableListOf<ChapterInfo>()
            var start = 0
            var num = 1
            while (start < words.size) {
                val end = (start + step - 1).coerceAtMost(words.lastIndex)
                chapters.add(ChapterInfo("Parte $num", start, end))
                start += step
                num++
            }
            BookItem(
                id = fileName,
                title = fileName.substringBeforeLast('.').uppercase(),
                words = words,
                chapters = chapters.ifEmpty { listOf(ChapterInfo("Capitolo 1", 0, words.lastIndex)) }
            )
        }
    }

    private fun parseEpubStream(inputStream: InputStream, fileName: String): BookItem {
        val zipEntries = mutableMapOf<String, String>()

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && (
                        name.endsWith(".xhtml", true) ||
                        name.endsWith(".html", true) ||
                        name.endsWith(".htm", true) ||
                        name.endsWith(".opf", true) ||
                        name.endsWith(".xml", true)
                    )
                ) {
                    zipEntries[name] = zip.bufferedReader(Charsets.UTF_8).readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val containerXml = zipEntries["META-INF/container.xml"] ?: ""
        val opfPath = Regex("""full-path=["']([^"']+)["']""")
            .find(containerXml)?.groupValues?.get(1)
            ?: zipEntries.keys.firstOrNull { it.endsWith(".opf", true) }

        val orderedChapterHtmls = mutableListOf<String>()

        if (opfPath != null && zipEntries.containsKey(opfPath)) {
            val opfContent = zipEntries[opfPath] ?: ""
            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            val manifestMap = mutableMapOf<String, String>()
            val itemRegex = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
            val idRegex = Regex("""\bid=["']([^"']+)["']""")
            val hrefRegex = Regex("""\bhref=["']([^"']+)["']""")

            itemRegex.findAll(opfContent).forEach { match ->
                val tag = match.value
                val id = idRegex.find(tag)?.groupValues?.get(1)
                val href = hrefRegex.find(tag)?.groupValues?.get(1)
                if (id != null && href != null) {
                    manifestMap[id] = URLDecoder.decode(href.substringBefore("#"), "UTF-8")
                }
            }

            val idrefRegex = Regex("""<itemref\b[^>]*\bidref=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            idrefRegex.findAll(opfContent).forEach { match ->
                val idref = match.groupValues[1]
                val relHref = manifestMap[idref]
                if (relHref != null) {
                    val fullPath = (opfDir + relHref).replace("//", "/")
                    val html = zipEntries[fullPath] ?: zipEntries[relHref]
                    if (html != null) orderedChapterHtmls.add(html)
                }
            }
        }

        if (orderedChapterHtmls.isEmpty()) {
            zipEntries.filterKeys {
                it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true)
            }.toSortedMap().values.forEach { orderedChapterHtmls.add(it) }
        }

        val allWords = mutableListOf<String>()
        val chapters = mutableListOf<ChapterInfo>()

        orderedChapterHtmls.forEach { html ->
            val extractedTitle = extractChapterHeading(html)
            val chapterWords = tokenize(stripHtmlTags(html))
            if (chapterWords.isNotEmpty()) {
                val startIdx = allWords.size
                allWords.addAll(chapterWords)
                val endIdx = allWords.lastIndex
                val chapNumber = chapters.size + 1
                val finalTitle = extractedTitle?.takeIf { it.length in 2..45 } ?: "Capitolo $chapNumber"
                chapters.add(ChapterInfo(finalTitle, startIdx, endIdx))
            }
        }

        if (allWords.isEmpty()) {
            allWords.addAll(listOf("Impossibile", "estrarre", "testo", "dall'EPUB."))
            chapters.add(ChapterInfo("Capitolo 1", 0, allWords.lastIndex))
        }

        return BookItem(
            id = fileName,
            title = fileName.substringBeforeLast('.').uppercase(),
            words = allWords,
            chapters = chapters
        )
    }

    private fun extractChapterHeading(html: String): String? {
        val headingRegex = Regex("""<(h1|h2|h3|title)[^>]*>(.*?)</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (match in headingRegex.findAll(html)) {
            val raw = stripHtmlTags(match.groupValues[2]).trim().replace(Regex("\\s+"), " ")
            if (raw.isNotBlank() && !raw.equals("unknown", true) && !raw.endsWith(".xhtml", true)) {
                return raw
            }
        }
        return null
    }

    private fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("(?s)<head.*?>.*?</head>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<style.*?>.*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<script.*?>.*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&laquo;", "\"")
            .replace("&raquo;", "\"")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    fun tokenize(text: String): List<String> {
        return text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun getDemoBook(): BookItem {
        val ch1 = tokenize(
            "HEADLINE delivers to Spritz, flashes right sul tuo OnePlus Watch 3! " +
            "La densità di questa app è bloccata nativamente a 320 DPI anche se il sistema è a 240 DPI."
        )
        val ch2 = tokenize(
            "Ruota la corona per scorrere le parole al tuo ritmo. " +
            "Tocca il titolo del capitolo in alto per aprire la lista completa dei capitoli e saltare dove vuoi!"
        )
        val ch3 = tokenize(
            "Nella schermata Libreria ora vedi la copertina di ogni libro EPUB " +
            "con la percentuale di completamento sempre aggiornata."
        )
        val all = ch1 + ch2 + ch3
        return BookItem(
            id = "demo_spritz",
            title = "SPRITZ WEAR",
            words = all,
            chapters = listOf(
                ChapterInfo("1. Introduzione 320 DPI", 0, ch1.size - 1),
                ChapterInfo("2. Controllo Corona", ch1.size, ch1.size + ch2.size - 1),
                ChapterInfo("3. Copertine & Capitoli", ch1.size + ch2.size, all.lastIndex)
            )
        )
    }
}

class WifiBookServer(
    private val context: Context,
    private val onBookUploaded: (File) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun start(port: Int = 8080): String {
        stop()
        isRunning = true
        thread(isDaemon = true) {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    thread(isDaemon = true) { handleClient(client) }
                }
            } catch (_: Exception) {
            }
        }
        return "http://${getLocalIpAddress()}:$port"
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(socket: java.net.Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val headerBytes = java.io.ByteArrayOutputStream()
            var prev3 = 0; var prev2 = 0; var prev1 = 0; var curr: Int
            while (input.read().also { curr = it } != -1) {
                headerBytes.write(curr)
                if (prev3 == 13 && prev2 == 10 && prev1 == 13 && curr == 10) break
                prev3 = prev2; prev2 = prev1; prev1 = curr
            }

            val headerText = headerBytes.toString("UTF-8")
            val firstLine = headerText.lines().firstOrNull() ?: return

            if (firstLine.startsWith("POST /upload")) {
                val rawName = firstLine.substringAfter("name=", "libro.epub").substringBefore(" ")
                val decodedName = URLDecoder.decode(rawName, "UTF-8")
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")

                val contentLength = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val targetDir = File(context.filesDir, "SpritzBooks").apply { mkdirs() }
                val destFile = File(targetDir, decodedName)
                destFile.outputStream().use { fileOut ->
                    val buffer = ByteArray(4096)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read == -1) break
                        fileOut.write(buffer, 0, read)
                        remaining -= read
                    }
                }

                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nOK"
                output.write(resp.toByteArray())
                output.flush()
                onBookUploaded(destFile)
            } else {
                val html = """
                    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>SpritzWear Cartella SpritzBooks</title>
                    <style>body{background:#111;color:#fff;font-family:sans-serif;text-align:center;padding:30px}
                    .box{max-width:360px;margin:auto;background:#1e1e1e;padding:24px;border-radius:16px}
                    button{background:#e53935;color:#fff;border:0;padding:12px 24px;border-radius:8px;font-size:16px;margin-top:16px;cursor:pointer}</style>
                    </head><body><div class="box"><h2>SpritzWear Watch 3</h2>
                    <p>Invia un file <b>.epub</b> o <b>.txt</b> direttamente nella cartella <b>SpritzBooks</b>:</p>
                    <input type="file" id="f" accept=".epub,.txt"><br>
                    <button onclick="up()">Carica in SpritzBooks</button>
                    <p id="s"></p></div>
                    <script>
                    async function up(){
                      const f=document.getElementById('f').files[0];
                      if(!f)return alert('Scegli un file!');
                      document.getElementById('s').innerText='Invio in corso...';
                      const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});
                      if(r.ok) document.getElementById('s').innerText='Salvataggio completato!';
                    }
                    </script></body></html>
                """.trimIndent()
                val bytes = html.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n"
                output.write(header.toByteArray())
                output.write(bytes)
                output.flush()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "IP_NON_TROVATO"
    }
}
