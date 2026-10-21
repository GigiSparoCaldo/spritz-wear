package com.spritzwear.reader

import android.content.Context
import android.net.Uri
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
import kotlin.math.roundToLong

data class SpritzWord(
    val prefix: String,
    val pivot: String,
    val suffix: String,
    val delayMultiplier: Float
)

data class BookItem(
    val id: String,
    val title: String,
    val words: List<String>,
    val chapterIndices: List<Int> = listOf(0)
)

object BookParser {

    /**
     * Calcola l'Optimal Recognition Point (ORP) per allineare la lettera chiave rossa
     * esattamente sotto il mirino centrale.
     */
    fun toSpritzWord(rawWord: String): SpritzWord {
        val word = rawWord.trim()
        if (word.isEmpty()) return SpritzWord("", "", "", 1.0f)

        val cleanLength = word.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'', ')', ']').length
        val pivotIndex = when {
            cleanLength <= 1 -> 0
            cleanLength in 2..5 -> 1
            cleanLength in 6..9 -> 2
            cleanLength in 10..13 -> 3
            else -> 4
        }.coerceAtMost(word.length - 1)

        val prefix = word.substring(0, pivotIndex)
        val pivot = word.substring(pivotIndex, pivotIndex + 1)
        val suffix = if (pivotIndex + 1 < word.length) word.substring(pivotIndex + 1) else ""

        val lastChar = word.last()
        var multiplier = when (lastChar) {
            '.', '!', '?' -> 2.3f
            ';', ':' -> 1.8f
            ',' -> 1.5f
            else -> 1.0f
        }
        if (cleanLength > 8) multiplier *= 1.35f

        return SpritzWord(prefix, pivot, suffix, multiplier)
    }

    fun calculateDelayMs(wpm: Int, multiplier: Float, rampFactor: Float = 1.0f): Long {
        val effectiveWpm = (wpm * rampFactor).coerceAtLeast(60f)
        val baseMs = 60_000f / effectiveWpm
        return (baseMs * multiplier).roundToLong()
    }

    fun importUriToInternalStorage(context: Context, uri: Uri, displayName: String): BookItem {
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destFile = File(context.filesDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return parseFile(destFile)
    }

    fun parseFile(file: File): BookItem {
        if (!file.exists()) return getDemoBook()
        return file.inputStream().use { parseStream(it, file.name) }
    }

    private fun parseStream(inputStream: InputStream, fileName: String): BookItem {
        return if (fileName.lowercase().endsWith(".epub")) {
            parseEpubStream(inputStream, fileName)
        } else {
            val text = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            val words = tokenize(text)
            // Crea capitoli virtuali ogni 1500 parole per i file .txt
            val chapters = words.indices.step(1500).toList().ifEmpty { listOf(0) }
            BookItem(
                id = fileName,
                title = fileName.substringBeforeLast('.').uppercase(),
                words = words.ifEmpty { listOf("File", "vuoto.") },
                chapterIndices = chapters
            )
        }
    }

    /**
     * Legge tutti i file dell'EPUB in memoria, risolve l'ordine esatto dei capitoli
     * tramite il file OPF (<manifest> + <spine>) e calcola gli indici di inizio capitolo.
     */
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

        // 1. Trova il percorso del file .opf da META-INF/container.xml
        val containerXml = zipEntries["META-INF/container.xml"] ?: ""
        val opfPath = Regex("""full-path=["']([^"']+)["']""")
            .find(containerXml)?.groupValues?.get(1)
            ?: zipEntries.keys.firstOrNull { it.endsWith(".opf", true) }

        val orderedChapterHtmls = mutableListOf<String>()

        if (opfPath != null && zipEntries.containsKey(opfPath)) {
            val opfContent = zipEntries[opfPath] ?: ""
            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            // Costruisce la mappa ID -> href dal <manifest>
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

            // Legge la sequenza <spine> (<itemref idref="..." />)
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

        // Fallback se l'EPUB non ha uno spine standard
        if (orderedChapterHtmls.isEmpty()) {
            zipEntries.filterKeys {
                it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true)
            }.toSortedMap().values.forEach { orderedChapterHtmls.add(it) }
        }

        val allWords = mutableListOf<String>()
        val chapterStartIndices = mutableListOf<Int>()

        orderedChapterHtmls.forEach { html ->
            val chapterWords = tokenize(stripHtmlTags(html))
            if (chapterWords.isNotEmpty()) {
                chapterStartIndices.add(allWords.size)
                allWords.addAll(chapterWords)
            }
        }

        if (allWords.isEmpty()) {
            allWords.addAll(listOf("Impossibile", "estrarre", "testo", "dall'EPUB."))
            chapterStartIndices.add(0)
        }

        return BookItem(
            id = fileName,
            title = fileName.substringBeforeLast('.').uppercase(),
            words = allWords,
            chapterIndices = chapterStartIndices
        )
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

    fun listSavedBooks(context: Context): List<File> {
        val internalFiles = context.filesDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".txt", true) || file.name.endsWith(".epub", true))
        }?.toList() ?: emptyList()

        val externalDirs = listOf(
            context.getExternalFilesDir(null),
            File("/sdcard/Download")
        )
        val externalFiles = externalDirs.filterNotNull().flatMap { dir ->
            dir.listFiles { file ->
                file.isFile && (file.name.endsWith(".txt", true) || file.name.endsWith(".epub", true))
            }?.toList() ?: emptyList()
        }

        return (internalFiles + externalFiles).distinctBy { it.name }
    }

    fun getDemoBook(): BookItem {
        val sample = """
            HEADLINE delivers to Spritz, flashes right sul tuo OnePlus Watch 3!
            Mentre la lettura è in Play, gira la corona per regolare i WPM in tempo reale.
            Appena metti in Pausa, la corona passa automaticamente allo scorrimento manuale parola per parola
            e in basso compare l'anteprima della frase completa per non perdere mai il filo!
            Le parole italiane molto lunghe come precipitevolissimevolmente si ridimensionano da sole
            per non uscire mai dai bordi rotondi dello smartwatch.
        """.trimIndent()
        return BookItem(
            id = "demo_spritz",
            title = "HEADLINE",
            words = tokenize(sample),
            chapterIndices = listOf(0, 22, 45)
        )
    }
}

/**
 * Mini Web Server HTTP su porta 8080 per caricare libri .epub e .txt
 * direttamente dal browser dello smartphone o del PC via Wi-Fi senza usare ADB.
 */
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

            // Legge l'header HTTP fino a \r\n\r\n
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

                val destFile = File(context.filesDir, decodedName)
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
                    <title>SpritzWear Wi-Fi Upload</title>
                    <style>body{background:#111;color:#fff;font-family:sans-serif;text-align:center;padding:30px}
                    .box{max-width:360px;margin:auto;background:#1e1e1e;padding:24px;border-radius:16px}
                    button{background:#e53935;color:#fff;border:0;padding:12px 24px;border-radius:8px;font-size:16px;margin-top:16px;cursor:pointer}</style>
                    </head><body><div class="box"><h2>SpritzWear Watch 3</h2>
                    <p>Seleziona un file <b>.epub</b> o <b>.txt</b> da inviare all'orologio:</p>
                    <input type="file" id="f" accept=".epub,.txt"><br>
                    <button onclick="up()">Invia a OnePlus Watch 3</button>
                    <p id="s"></p></div>
                    <script>
                    async function up(){
                      const f=document.getElementById('f').files[0];
                      if(!f)return alert('Scegli un file!');
                      document.getElementById('s').innerText='Invio in corso...';
                      const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});
                      if(r.ok) document.getElementById('s').innerText='Caricato! Guarda lo smartwatch.';
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
