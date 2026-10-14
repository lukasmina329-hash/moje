package pl.firma.smskody

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Odbiera każdy przychodzący SMS (także gdy aplikacja jest zamknięta) i oddaje go do Engine. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = Prefs(context)
        if (!prefs.enabled) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return

        // SMS wieloczęściowy przychodzi jako kilka kawałków od tego samego nadawcy – sklejamy je.
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (m in msgs) {
            val from = m.originatingAddress ?: ""
            bySender.getOrPut(from) { StringBuilder() }.append(m.messageBody ?: "")
        }

        val pending = goAsync()
        val appCtx = context.applicationContext
        Thread {
            try {
                synchronized(Engine.LOCK) {
                    val engine = Engine(prefs.config(), AndroidEnv(appCtx, prefs))
                    for ((sender, body) in bySender) {
                        if (sender.isEmpty()) continue
                        try {
                            engine.onSms(sender, body.toString())
                        } catch (e: Exception) {
                            AppLog.add(appCtx, "Błąd obsługi SMS od $sender: ${e.message}")
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
