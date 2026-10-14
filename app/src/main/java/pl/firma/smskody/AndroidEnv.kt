package pl.firma.smskody

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/** Łączy czystą logikę (Engine) z Androidem: pamięć, wysyłka SMS, dziennik. */
class AndroidEnv(private val ctx: Context, private val prefs: Prefs) : Env {

    override fun now(): Long = System.currentTimeMillis()

    override fun loadTx(): List<Tx> = prefs.loadTx()

    override fun saveTx(list: List<Tx>) = prefs.saveTx(list)

    override fun sendSms(to: String, text: String) {
        sendTextSms(ctx, to, text)
    }

    override fun log(line: String) {
        AppLog.add(ctx, line)
    }

    companion object {
        @Suppress("DEPRECATION")
        fun sendTextSms(ctx: Context, to: String, text: String) {
            val fromService: SmsManager? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java) else null
            val sm: SmsManager = fromService ?: SmsManager.getDefault()
            val parts = sm.divideMessage(text)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                sm.sendTextMessage(to, null, text, null, null)
            }
        }
    }
}
