package pl.firma.smskody

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prosty dziennik zdarzeń w pliku. Kody SMS nigdy nie trafiają do dziennika. */
object AppLog {
    private const val FILE = "log.txt"
    private const val MAX_BYTES = 200_000L
    private const val KEEP_LINES = 500

    @Synchronized
    fun add(ctx: Context, line: String) {
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(KEEP_LINES)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.forLanguageTag("pl-PL")).format(Date())
            f.appendText("$stamp  $line\n")
        } catch (e: Exception) {
            // dziennik nie może wywrócić obsługi SMS
        }
    }

    /** Ostatnie [n] wpisów, najnowszy na górze. */
    @Synchronized
    fun tail(ctx: Context, n: Int): String {
        return try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (!f.exists()) "" else f.readLines().takeLast(n).reversed().joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            File(ctx.applicationContext.filesDir, FILE).delete()
        } catch (e: Exception) {
        }
    }
}
