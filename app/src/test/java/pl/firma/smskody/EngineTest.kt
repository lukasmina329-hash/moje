package pl.firma.smskody

import org.junit.Assert
import org.junit.Test

class FakeEnv(var t: Long = 1_000_000L) : Env {
    val store = ArrayList<Tx>()
    val sent = ArrayList<Pair<String, String>>()
    val logs = ArrayList<String>()
    var failFor: String? = null

    override fun now(): Long = t
    override fun loadTx(): List<Tx> = ArrayList(store)
    override fun saveTx(list: List<Tx>) {
        store.clear()
        store.addAll(list)
    }
    override fun sendSms(to: String, text: String) {
        val f = failFor
        if (f != null && Phones.key(to) == Phones.key(f)) throw RuntimeException("boom")
        sent.add(Pair(to, text))
    }
    override fun log(line: String) {
        logs.add(line)
    }

    /** Wiadomości wysłane do danego numeru (porównanie po znormalizowanym numerze). */
    fun to(number: String): List<String> =
        sent.filter { Phones.key(it.first) == Phones.key(number) }.map { it.second }
}

class EngineTest {

    private val JAN = "+48600100200"
    private val OLA = "+48601200300"
    private val PIOTR = "+48602300400"

    private val plannersText = "Jan;+48 600 100 200\nOla;601 200 300\nPiotr;602-300-400"

    private fun cfg(
        showDetails: Boolean = false,
        notifyOthers: Boolean = false,
        expiry: Int = 15
    ) = Config(
        bankSenders = "Millennium,Millenium",
        planners = Planners.parse(plannersText),
        expiryMinutes = expiry,
        showDetails = showDetails,
        notifyOthers = notifyOthers
    )

    private fun bank(op: Int, code: String, merchant: String = "", amount: String = ""): String =
        "Operacja nr: $op z dn. 13.10.2026 14:12:07\n" +
            "Card payments\n" +
            "nr karty: \n" +
            "sprzedawca : $merchant\n" +
            "kwota : $amount\n" +
            "HasłoSMS: $code"

    private fun engine(env: FakeEnv, c: Config = cfg()) = Engine(c, env)

    // ------------------------------------------------------------- parsowanie

    @Test
    fun parsesSampleSmsWithEmptyFields() {
        val b = Parser.parseBank(bank(15, "111111"))
        Assert.assertNotNull(b)
        Assert.assertEquals("15", b!!.op)
        Assert.assertEquals("111111", b.code)
        Assert.assertEquals("", b.merchant)
        Assert.assertEquals("", b.amount)
    }

    @Test
    fun parsesMerchantAndAmount() {
        val b = Parser.parseBank(bank(16, "222222", "ALLEGRO", "129,99 PLN"))!!
        Assert.assertEquals("ALLEGRO", b.merchant)
        Assert.assertEquals("129,99 PLN", b.amount)
    }

    @Test
    fun parsesUppercaseAndSpacedPasswordLabel() {
        val b = Parser.parseBank("Operacja nr: 7\nHASŁO SMS: 123456")
        Assert.assertNotNull(b)
        Assert.assertEquals("123456", b!!.code)
    }

    @Test
    fun smsWithoutPasswordIsNotBankCode() {
        Assert.assertNull(Parser.parseBank("Operacja nr: 15 z dn. 13.10.2026"))
        Assert.assertNull(Parser.parseBank("Cześć, HasłoSMS: 111111"))
    }

    @Test
    fun takVariants() {
        Assert.assertEquals(null, Parser.parseTak("TAK")!!.op)
        Assert.assertEquals(null, Parser.parseTak(" tak ")!!.op)
        Assert.assertEquals(null, Parser.parseTak("Tak.")!!.op)
        Assert.assertEquals("15", Parser.parseTak("TAK 15")!!.op)
        Assert.assertEquals("15", Parser.parseTak(" tak15 ")!!.op)
        Assert.assertEquals("15", Parser.parseTak("TAK nr 15")!!.op)
        Assert.assertNull(Parser.parseTak("takt"))
        Assert.assertNull(Parser.parseTak("tak 15 proszę"))
        Assert.assertNull(Parser.parseTak("nie"))
    }

    @Test
    fun plannersParsing() {
        val p = Planners.parse("Jan Kowalski;+48 600 100 200\n\n601 200 300\nZły;123\nJan dubel;600100200")
        Assert.assertEquals(2, p.size)
        Assert.assertEquals("Jan Kowalski", p[0].name)
        Assert.assertEquals("601 200 300", p[1].name)
    }

    // ------------------------------------------------------------ przepływ

    @Test
    fun bankSmsNotifiesAllPlannersWithoutCode() {
        val env = FakeEnv()
        engine(env).onSms("Millennium", bank(15, "111111", "ALLEGRO", "10 PLN"))
        Assert.assertEquals(3, env.sent.size)
        for ((_, text) in env.sent) {
            Assert.assertTrue(text, text.contains("TAK 15"))
            Assert.assertFalse(text, text.contains("111111"))
            Assert.assertFalse(text, text.contains("ALLEGRO"))
        }
        Assert.assertEquals(
            listOf(JAN, OLA, PIOTR).map { Phones.key(it) },
            env.sent.map { Phones.key(it.first) }
        )
    }

    @Test
    fun detailsShownWhenEnabled() {
        val env = FakeEnv()
        engine(env, cfg(showDetails = true)).onSms("Millennium", bank(15, "111111", "ALLEGRO", "10 PLN"))
        Assert.assertTrue(env.sent[0].second.contains("ALLEGRO, 10 PLN"))
        Assert.assertFalse(env.sent[0].second.contains("111111"))
    }

    @Test
    fun firstClaimWinsAndOthersAreTold() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.sent.clear()

        e.onSms(JAN, "TAK 15")
        Assert.assertEquals(1, env.sent.size)
        Assert.assertEquals(Phones.key(JAN), Phones.key(env.sent[0].first))
        Assert.assertTrue(env.sent[0].second.contains("111111"))

        e.onSms(OLA, "TAK 15")
        val olaMsgs = env.to(OLA)
        Assert.assertEquals(1, olaMsgs.size)
        Assert.assertTrue(olaMsgs[0].contains("przejęty przez Jan"))
        Assert.assertFalse(olaMsgs[0].contains("111111"))
    }

    @Test
    fun plainTakWorksWithSinglePendingCode() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.sent.clear()
        e.onSms(OLA, "tak")
        Assert.assertEquals("Kod nr 15: 111111", env.to(OLA)[0])
    }

    @Test
    fun plainTakWithSeveralPendingAsksForNumber() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.t += 5_000
        e.onSms("Millennium", bank(16, "222222"))
        env.sent.clear()

        e.onSms(JAN, "TAK")
        Assert.assertEquals(1, env.sent.size)
        Assert.assertTrue(env.sent[0].second.contains("15, 16"))
        Assert.assertTrue(env.store.all { it.claimedNum == null })

        e.onSms(JAN, "TAK 16")
        Assert.assertTrue(env.to(JAN).last().contains("222222"))
        Assert.assertFalse(env.to(JAN).last().contains("111111"))
    }

    @Test
    fun twoPlannersTwoPurchasesGetTheirOwnCodes() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.t += 3_000
        e.onSms("Millennium", bank(16, "222222"))
        env.sent.clear()
        e.onSms(OLA, "TAK 16")
        e.onSms(JAN, "TAK 15")
        Assert.assertTrue(env.to(OLA)[0].contains("222222"))
        Assert.assertTrue(env.to(JAN)[0].contains("111111"))
    }

    @Test
    fun sameClaimerCanGetCodeAgain() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        e.onSms(JAN, "TAK 15")
        env.sent.clear()
        e.onSms(JAN, "TAK 15")
        Assert.assertTrue(env.to(JAN)[0].contains("111111"))
        env.sent.clear()
        e.onSms(JAN, "TAK") // samo TAK, nic nie czeka – oddaj ostatnio przejęty
        Assert.assertTrue(env.to(JAN)[0].contains("111111"))
    }

    @Test
    fun codeExpires() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.t += 16 * 60_000L
        env.sent.clear()
        e.onSms(JAN, "TAK 15")
        Assert.assertTrue(env.to(JAN)[0].contains("wygasł"))
        Assert.assertFalse(env.to(JAN)[0].contains("111111"))
    }

    @Test
    fun expiryZeroMeansNeverExpires() {
        val env = FakeEnv()
        val e = engine(env, cfg(expiry = 0))
        e.onSms("Millennium", bank(15, "111111"))
        env.t += 5 * 60 * 60_000L
        env.sent.clear()
        e.onSms(JAN, "TAK 15")
        Assert.assertTrue(env.to(JAN)[0].contains("111111"))
    }

    @Test
    fun codesOlderThan24hAreDropped() {
        val env = FakeEnv()
        val e = engine(env, cfg(expiry = 0))
        e.onSms("Millennium", bank(15, "111111"))
        env.t += 25 * 60 * 60_000L
        env.sent.clear()
        e.onSms(JAN, "TAK 15")
        Assert.assertTrue(env.to(JAN)[0].contains("Nie ma kodu"))
    }

    @Test
    fun duplicateDeliveryIsIgnored() {
        val env = FakeEnv()
        val e = engine(env)
        val sms = bank(15, "111111")
        e.onSms("Millennium", sms)
        e.onSms("Millennium", sms)
        Assert.assertEquals(3, env.sent.size)
        Assert.assertEquals(1, env.store.size)
    }

    @Test
    fun unknownNumberCannotClaim() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.sent.clear()
        e.onSms("+48999888777", "TAK 15")
        Assert.assertEquals(0, env.sent.size)
        Assert.assertTrue(env.logs.last().contains("nieznanego numeru"))
    }

    @Test
    fun senderFilter() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Oszust", bank(15, "111111"))
        Assert.assertEquals(0, env.sent.size)
        e.onSms("BankMillennium", bank(16, "222222"))
        Assert.assertEquals(3, env.sent.size)
    }

    @Test
    fun emptySenderFilterAcceptsAnyBankFormattedSms() {
        val env = FakeEnv()
        Engine(cfg().copy(bankSenders = ""), env).onSms("cokolwiek", bank(15, "111111"))
        Assert.assertEquals(3, env.sent.size)
    }

    @Test
    fun plannerNumberFormatsAreMatched() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.sent.clear()
        e.onSms("+48601200300", "TAK 15") // Ola zapisana jako "601 200 300"
        Assert.assertEquals(1, env.sent.size)
        Assert.assertEquals("601200300", env.sent[0].first) // wysyłka na numer tak, jak zapisano na liście
        Assert.assertTrue(env.sent[0].second.contains("111111"))
    }

    @Test
    fun plannerChatterIsIgnored() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "111111"))
        env.sent.clear()
        e.onSms(JAN, "Dzięki, zaraz podam")
        Assert.assertEquals(0, env.sent.size)
    }

    @Test
    fun notifyOthersWhenEnabled() {
        val env = FakeEnv()
        val e = engine(env, cfg(notifyOthers = true))
        e.onSms("Millennium", bank(15, "111111"))
        env.sent.clear()
        e.onSms(JAN, "TAK 15")
        Assert.assertTrue(env.to(OLA)[0].contains("przejęty przez Jan"))
        Assert.assertTrue(env.to(PIOTR)[0].contains("przejęty przez Jan"))
        Assert.assertEquals(1, env.to(JAN).size)
    }

    @Test
    fun sendFailureForOnePlannerDoesNotStopOthers() {
        val env = FakeEnv()
        env.failFor = OLA
        engine(env).onSms("Millennium", bank(15, "111111"))
        Assert.assertEquals(2, env.sent.size)
        Assert.assertTrue(env.logs.any { it.contains("Błąd wysyłki") })
        Assert.assertTrue(env.logs.any { it.contains("2/3") })
    }

    @Test
    fun codeNeverAppearsInLogs() {
        val env = FakeEnv()
        val e = engine(env)
        e.onSms("Millennium", bank(15, "987654"))
        e.onSms(JAN, "TAK 15")
        e.onSms(OLA, "TAK 15")
        Assert.assertTrue(env.logs.none { it.contains("987654") })
    }

    @Test
    fun noPlannersConfigured() {
        val env = FakeEnv()
        Engine(cfg().copy(planners = emptyList()), env).onSms("Millennium", bank(15, "111111"))
        Assert.assertEquals(0, env.sent.size)
        Assert.assertTrue(env.logs.last().contains("brak planistów"))
    }
}
