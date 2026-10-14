package pl.firma.smskody

// Cała logika biznesowa jest tutaj i NIE używa niczego z Androida,
// dzięki czemu da się ją testować zwykłym testem jednostkowym (EngineTest).

data class Planner(val name: String, val number: String)

data class Config(
    val bankSenders: String = "Millennium,Millenium",
    val planners: List<Planner> = emptyList(),
    val expiryMinutes: Int = 15,        // 0 = kod nie wygasa
    val showDetails: Boolean = false,   // czy pierwszy SMS do planistów ma zawierać sprzedawcę i kwotę
    val notifyOthers: Boolean = false   // czy informować pozostałych, kto przejął kod
)

data class BankSms(val op: String, val code: String, val merchant: String, val amount: String)

data class Tak(val op: String?)

data class Tx(
    val op: String,
    val code: String,
    val merchant: String,
    val amount: String,
    val fullText: String,
    val receivedAt: Long,
    val claimedBy: String? = null,
    val claimedNum: String? = null,
    val claimedAt: Long = 0L
)

interface Env {
    fun now(): Long
    fun loadTx(): List<Tx>
    fun saveTx(list: List<Tx>)
    fun sendSms(to: String, text: String)
    fun log(line: String)
}

object Phones {
    /** Klucz do porównywania numerów: ostatnie 9 cyfr (ignoruje +48, 0048, spacje, myślniki). */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(9)

    /** Numer w postaci przyjmowanej przez SmsManager. */
    fun dial(number: String): String = number.filter { it.isDigit() || it == '+' }
}

object Planners {
    private val RE_NUMBER = Regex("""(\+?\d[\d\s-]{7,}\d)\s*$""")

    /** Jedna osoba w linii: "Imię;numer" (albo samo "numer"). */
    fun parse(text: String): List<Planner> {
        val out = ArrayList<Planner>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val name: String
            val number: String
            val semi = line.lastIndexOf(';')
            if (semi >= 0) {
                name = line.substring(0, semi).trim()
                number = line.substring(semi + 1).trim()
            } else {
                val m = RE_NUMBER.find(line) ?: continue
                number = m.groupValues[1].trim()
                name = line.substring(0, m.range.first).trim().trimEnd(',', ':', '-', ' ', '\t')
            }
            if (Phones.key(number).length < 9) continue
            if (out.any { Phones.key(it.number) == Phones.key(number) }) continue
            out.add(Planner(if (name.isEmpty()) number else name, number))
        }
        return out
    }
}

object Parser {
    private val RE_OP = Regex("""Operacja\s*(?:nr|numer)?\.?[ \t]*:?[ \t]*(\d+)""", RegexOption.IGNORE_CASE)
    private val RE_CODE = Regex("""Has[łl]o[ \t]*SMS[ \t]*:?[ \t]*(\w+)""", RegexOption.IGNORE_CASE)
    private val RE_MERCHANT = Regex("""sprzedawca[ \t]*:[ \t]*([^\r\n]*)""", RegexOption.IGNORE_CASE)
    private val RE_AMOUNT = Regex("""kwota[ \t]*:[ \t]*([^\r\n]*)""", RegexOption.IGNORE_CASE)
    private val RE_TAK = Regex("""^\s*tak[\s,.:;#-]*(?:nr\.?\s*)?(\d{1,6})?\s*[.!]*\s*$""", RegexOption.IGNORE_CASE)

    /** Rozpoznaje SMS z kodem z banku. Wymaga numeru operacji i hasła. */
    fun parseBank(body: String): BankSms? {
        val op = RE_OP.find(body)?.groupValues?.get(1) ?: return null
        val code = RE_CODE.find(body)?.groupValues?.get(1) ?: return null
        val merchant = RE_MERCHANT.find(body)?.groupValues?.get(1)?.trim() ?: ""
        val amount = RE_AMOUNT.find(body)?.groupValues?.get(1)?.trim() ?: ""
        return BankSms(op, code, merchant, amount)
    }

    /** Rozpoznaje odpowiedź planisty: "TAK", "TAK 15", "tak15", "TAK nr 15". */
    fun parseTak(body: String): Tak? {
        val m = RE_TAK.find(body) ?: return null
        val op = m.groupValues[1]
        return Tak(if (op.isEmpty()) null else op)
    }

    /** Filtr nadawcy: lista fragmentów po przecinku; pusty filtr = dowolny nadawca. */
    fun senderMatches(filters: String, sender: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val wanted = filters.split(',', ';').map { norm(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return true
        val s = norm(sender)
        return wanted.any { s.contains(it) }
    }
}

class Engine(private val cfg: Config, private val env: Env) {

    companion object {
        /** Wspólna blokada: odbiornik SMS i przycisk testowy nie mogą działać jednocześnie. */
        val LOCK = Any()
        const val KEEP_MS = 24L * 60 * 60 * 1000
        private const val DUPLICATE_WINDOW_MS = 120_000L
    }

    /** Punkt wejścia: każdy odebrany SMS trafia tutaj. */
    fun onSms(sender: String, body: String) {
        val planner = findPlanner(sender)
        if (planner != null) {
            val tak = Parser.parseTak(body)
            if (tak != null) {
                handleTak(planner, tak.op)
            } else {
                env.log("Planista ${planner.name}: wiadomość nie jest poleceniem TAK – pominięto")
            }
            return
        }

        val bankFilterSet = cfg.bankSenders.isNotBlank()
        val bank = Parser.parseBank(body)
        if (bank != null) {
            if (Parser.senderMatches(cfg.bankSenders, sender)) {
                handleBank(bank, body)
            } else {
                env.log("SMS z kodem od nadawcy „$sender” – pominięto (nie pasuje do ustawienia banku)")
            }
            return
        }

        if (bankFilterSet && Parser.senderMatches(cfg.bankSenders, sender)) {
            env.log("SMS od „$sender” pasuje do banku, ale nie rozpoznano formatu (brak „Operacja nr” lub „HasłoSMS”)")
            return
        }

        if (Parser.parseTak(body) != null) {
            env.log("TAK od nieznanego numeru $sender – zignorowano (brak na liście planistów)")
        }
    }

    // ---------------------------------------------------------------- bank

    private fun handleBank(b: BankSms, body: String) {
        val now = env.now()
        val list = pruned(env.loadTx(), now)

        if (list.any { it.fullText == body && now - it.receivedAt < DUPLICATE_WINDOW_MS }) {
            env.log("Duplikat SMS z banku (operacja ${b.op}) – pominięto")
            return
        }

        val tx = Tx(b.op, b.code, b.merchant, b.amount, body, now)
        env.saveTx(list + tx)

        if (cfg.planners.isEmpty()) {
            env.log("Operacja ${b.op}: brak planistów w ustawieniach – nikogo nie powiadomiono")
            return
        }

        val d = if (cfg.showDetails) details(b.merchant, b.amount) else ""
        val text = "Nowy kod nr ${b.op}" +
            (if (d.isNotEmpty()) " ($d)" else "") +
            ". Aby go otrzymać, wyślij SMS: TAK ${b.op}"

        var ok = 0
        for (p in cfg.planners) if (send(p.number, text)) ok++
        env.log("Operacja ${b.op}: powiadomiono $ok/${cfg.planners.size} planistów")
    }

    // ----------------------------------------------------------------- TAK

    private fun handleTak(p: Planner, opArg: String?) {
        val now = env.now()
        val list = pruned(env.loadTx(), now)

        if (opArg != null) {
            val tx = list.filter { sameOp(it.op, opArg) }.maxByOrNull { it.receivedAt }
            if (tx == null) {
                reply(p, "Nie ma kodu nr $opArg (mógł wygasnąć). Poczekaj na nowy SMS.")
                env.log("${p.name}: prosi o kod $opArg – nie ma takiego")
                return
            }
            serve(p, tx, list, now)
            return
        }

        val open = list.filter { it.claimedNum == null && !expired(it, now) }.sortedBy { it.receivedAt }
        when {
            open.size == 1 -> serve(p, open[0], list, now)
            open.size > 1 -> {
                reply(p, "Oczekuje kilka kodów: ${open.joinToString(", ") { it.op }}. Wyślij TAK i numer, np. TAK ${open.last().op}.")
                env.log("${p.name}: samo TAK przy kilku oczekujących kodach – poproszono o numer")
            }
            else -> {
                val mineKey = Phones.key(p.number)
                val mine = list.filter { it.claimedNum == mineKey && !expired(it, now) }.maxByOrNull { it.receivedAt }
                if (mine != null) {
                    serve(p, mine, list, now)
                } else {
                    reply(p, "Brak oczekujących kodów.")
                    env.log("${p.name}: TAK, ale brak oczekujących kodów")
                }
            }
        }
    }

    private fun serve(p: Planner, tx: Tx, list: List<Tx>, now: Long) {
        val mineKey = Phones.key(p.number)

        if (tx.claimedNum != null && tx.claimedNum != mineKey) {
            reply(p, "Kod nr ${tx.op} został już przejęty przez ${tx.claimedBy}.")
            env.log("${p.name}: próba przejęcia kodu ${tx.op} – już przejęty przez ${tx.claimedBy}")
            return
        }

        if (expired(tx, now)) {
            reply(p, "Kod nr ${tx.op} wygasł (ważny ${cfg.expiryMinutes} min).")
            env.log("${p.name}: kod ${tx.op} wygasł")
            return
        }

        if (tx.claimedNum == null) {
            val updated = tx.copy(claimedBy = p.name, claimedNum = mineKey, claimedAt = now)
            env.saveTx(list.map { if (it === tx) updated else it })
            env.log("${p.name} przejął kod nr ${tx.op}")
            reply(p, codeText(updated))
            if (cfg.notifyOthers) {
                for (o in cfg.planners) {
                    if (Phones.key(o.number) != mineKey) {
                        reply(o, "Kod nr ${tx.op} przejęty przez ${p.name}.")
                    }
                }
            }
        } else {
            env.log("${p.name}: ponowne wysłanie kodu ${tx.op}")
            reply(p, codeText(tx))
        }
    }

    // ------------------------------------------------------------- pomocnicze

    private fun findPlanner(sender: String): Planner? {
        val k = Phones.key(sender)
        if (k.length < 6) return null
        return cfg.planners.firstOrNull { Phones.key(it.number) == k }
    }

    private fun pruned(list: List<Tx>, now: Long): List<Tx> = list.filter { now - it.receivedAt < KEEP_MS }

    private fun expired(tx: Tx, now: Long): Boolean =
        cfg.expiryMinutes > 0 && now - tx.receivedAt > cfg.expiryMinutes * 60_000L

    private fun sameOp(a: String, b: String): Boolean {
        fun n(s: String) = s.trimStart('0').ifEmpty { "0" }
        return n(a) == n(b)
    }

    private fun details(merchant: String, amount: String): String =
        listOf(merchant, amount).filter { it.isNotBlank() }.joinToString(", ")

    private fun codeText(tx: Tx): String {
        val d = details(tx.merchant, tx.amount)
        return "Kod nr ${tx.op}: ${tx.code}" + (if (d.isNotEmpty()) "\n$d" else "")
    }

    private fun reply(p: Planner, text: String) {
        send(p.number, text)
    }

    private fun send(to: String, text: String): Boolean {
        return try {
            env.sendSms(Phones.dial(to), text)
            true
        } catch (e: Exception) {
            env.log("Błąd wysyłki SMS do $to: ${e.message}")
            false
        }
    }
}
