package com.spritzwear.reader

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
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
    val words: List<String>
)

object BookParser {

    /**
     * Calcola l'Optimal Recognition Point (ORP) secondo il metodo Spritz:
     * la lettera rossa cade leggermente a sinistra del centro geometrico della parola.
     */
    fun toSpritzWord(rawWord: String): SpritzWord {
        val word = rawWord.trim()
        if (word.isEmpty()) return SpritzWord("", "", "", 1.0f)

        val cleanLength = word.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'', ')').length
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

        // Punteggiatura intelligente: aggiunge micro-pause naturali
        val lastChar = word.last()
        var multiplier = when (lastChar) {
            '.', '!', '?' -> 2.2f
            ';', ':' -> 1.7f
            ',' -> 1.5f
            else -> 1.0f
        }
        if (cleanLength > 8) multiplier *= 1.3f

        return SpritzWord(prefix, pivot, suffix, multiplier)
    }

    fun calculateDelayMs(wpm: Int, multiplier: Float, rampFactor: Float = 1.0f): Long {
        val effectiveWpm = (wpm * rampFactor).coerceAtLeast(60f)
        val baseMs = 60_000f / effectiveWpm
        return (baseMs * multiplier).roundToLong()
    }

    fun parseUri(context: Context, uri: Uri, displayName: String): BookItem {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return getDemoBook()
        return parseStream(inputStream, displayName)
    }

    fun parseFile(file: File): BookItem {
        return parseStream(file.inputStream(), file.name)
    }

    private fun parseStream(inputStream: InputStream, fileName: String): BookItem {
        val text = if (fileName.lowercase().endsWith(".epub")) {
            extractTextFromEpub(inputStream)
        } else {
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
        }
        val words = tokenize(text)
        return BookItem(
            id = fileName,
            title = fileName.substringBeforeLast('.').uppercase(),
            words = if (words.isNotEmpty()) words else listOf("File", "vuoto", "o", "non", "supportato.")
        )
    }

    /**
     * Estrae tutti i file XHTML/HTML dall'archivio ZIP dell'EPUB in ordine alfabetico/capitolo
     * e rimuove i tag HTML preservando le parole.
     */
    private fun extractTextFromEpub(inputStream: InputStream): String {
        val chapterEntries = mutableMapOf<String, String>()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                    val content = zip.bufferedReader(Charsets.UTF_8).readText()
                    val plainText = stripHtmlTags(content)
                    if (plainText.isNotBlank()) {
                        chapterEntries[entry.name] = plainText
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return chapterEntries.toSortedMap().values.joinToString(" ")
    }

    private fun stripHtmlTags(html: String): String {
        // Rimuove <head>, <style> e <script>
        val withoutHead = html
            .replace(Regex("(?s)<head.*?>.*?</head>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<style.*?>.*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<script.*?>.*?</script>", RegexOption.IGNORE_CASE), " ")

        // Sostituisce i tag HTML con spazi e decodifica le entità comuni
        return withoutHead
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    fun tokenize(text: String): List<String> {
        return text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun scanDownloadBooksFolder(): List<File> {
        val dirs = listOf(
            File("/sdcard/Download"),
            File("/sdcard/Documents"),
            File("/storage/emulated/0/Download")
        )
        return dirs.flatMap { dir ->
            dir.listFiles { file ->
                file.isFile && (file.name.endsWith(".txt", true) || file.name.endsWith(".epub", true))
            }?.toList() ?: emptyList()
        }.distinctBy { it.absolutePath }
    }

    fun getDemoBook(): BookItem {
        val sample = """
            HEADLINE delivers to Spritz, flashes right sul tuo OnePlus Watch 3 con Wear OS 5!
            Ruota la corona digitale per cambiare la velocità in WPM quando la lettura è attiva,
            oppure metti in pausa o passa alla modalità SCRUB per scorrere le parole avanti e indietro
            una alla volta con il movimento della corona.
            Il punto di riconoscimento ottimale, evidenziato in rosso sotto il mirino circolare,
            permette al tuo cervello di decodificare ogni parola istantaneamente senza muovere gli occhi.
            Puoi caricare qualsiasi libro in formato TXT o EPUB direttamente dalla memoria dell'orologio!
        """.trimIndent()
        return BookItem(
            id = "demo_spritz",
            title = "HEADLINE",
            words = tokenize(sample)
        )
    }
}
