package pl.firma.smskody

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ustawienia i oczekujące kody, zapisane w prywatnej pamięci aplikacji. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("kody_sms", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var bankSenders: String
        get() = sp.getString("bankSenders", "Millennium,Millenium") ?: ""
        set(v) { sp.edit().putString("bankSenders", v).apply() }

    var plannersText: String
        get() = sp.getString("plannersText", "") ?: ""
        set(v) { sp.edit().putString("plannersText", v).apply() }

    var expiryMinutes: Int
        get() = sp.getInt("expiryMinutes", 15)
        set(v) { sp.edit().putInt("expiryMinutes", v).apply() }

    var showDetails: Boolean
        get() = sp.getBoolean("showDetails", false)
        set(v) { sp.edit().putBoolean("showDetails", v).apply() }

    var notifyOthers: Boolean
        get() = sp.getBoolean("notifyOthers", false)
        set(v) { sp.edit().putBoolean("notifyOthers", v).apply() }

    fun config(): Config = Config(
        bankSenders = bankSenders,
        planners = Planners.parse(plannersText),
        expiryMinutes = expiryMinutes,
        showDetails = showDetails,
        notifyOthers = notifyOthers
    )

    fun loadTx(): List<Tx> {
        val raw = sp.getString("tx", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Tx>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val by = o.optString("claimedBy", "")
                val num = o.optString("claimedNum", "")
                out.add(
                    Tx(
                        op = o.getString("op"),
                        code = o.getString("code"),
                        merchant = o.optString("merchant", ""),
                        amount = o.optString("amount", ""),
                        fullText = o.optString("fullText", ""),
                        receivedAt = o.getLong("receivedAt"),
                        claimedBy = if (by.isEmpty()) null else by,
                        claimedNum = if (num.isEmpty()) null else num,
                        claimedAt = o.optLong("claimedAt", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTx(list: List<Tx>) {
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("op", t.op)
            o.put("code", t.code)
            o.put("merchant", t.merchant)
            o.put("amount", t.amount)
            o.put("fullText", t.fullText)
            o.put("receivedAt", t.receivedAt)
            o.put("claimedBy", t.claimedBy ?: "")
            o.put("claimedNum", t.claimedNum ?: "")
            o.put("claimedAt", t.claimedAt)
            arr.put(o)
        }
        // commit() zamiast apply(): zapis ma być na pewno zakończony, zanim odbiornik SMS się wyłączy
        sp.edit().putString("tx", arr.toString()).commit()
    }
}
