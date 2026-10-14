package pl.firma.smskody

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var swEnabled: Switch
    private lateinit var etBank: EditText
    private lateinit var etPlanners: EditText
    private lateinit var tvParsed: TextView
    private lateinit var etExpiry: EditText
    private lateinit var swDetails: Switch
    private lateinit var swNotify: Switch
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Kody SMS"
        buildUi()
        loadFields()
        if (!hasSmsPermissions()) askPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = if (bold) 16f else 13f
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(14), 0, dp(4))
        return tv
    }

    private fun edit(hint: String, multiline: Boolean = false, number: Boolean = false): EditText {
        val et = EditText(this)
        et.hint = hint
        et.inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            et.minLines = 4
            et.gravity = Gravity.TOP or Gravity.START
        }
        return et
    }

    private fun button(text: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { action() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(8), dp(16), dp(32))

        tvStatus = TextView(this)
        tvStatus.textSize = 14f
        tvStatus.setPadding(dp(12), dp(12), dp(12), dp(12))
        tvStatus.setBackgroundColor(0xFFEEEEEE.toInt())
        root.addView(tvStatus)

        swEnabled = Switch(this)
        swEnabled.text = "Aplikacja włączona (przetwarza SMS-y)"
        root.addView(swEnabled)

        root.addView(button("Nadaj uprawnienia SMS") { askPermissions() })
        root.addView(label("Jeśli okno uprawnień się nie pokazuje (Android 13+, aplikacja spoza Sklepu Play): Ustawienia → Aplikacje → Kody SMS → ⋮ → „Zezwól na ustawienia z ograniczeniami”, potem nadaj uprawnienia ponownie."))
        root.addView(button("Wyłącz oszczędzanie baterii dla aplikacji") { askBattery() })

        root.addView(label("Nadawca SMS z banku", bold = true))
        root.addView(label("Dokładnie tak, jak wyświetla telefon. Kilka nazw rozdziel przecinkiem. Puste pole = dowolny nadawca."))
        etBank = edit("np. Millennium,Millenium")
        root.addView(etBank)

        root.addView(label("Planiści (kto dostaje powiadomienia i może odpisać TAK)", bold = true))
        root.addView(label("Jedna osoba w linii: Imię;numer   (np. Jan;+48 600 100 200)"))
        etPlanners = edit("Jan;+48600100200", multiline = true)
        root.addView(etPlanners)
        tvParsed = label("")
        root.addView(tvParsed)

        root.addView(label("Ważność kodu (minuty, 0 = bez limitu)", bold = true))
        etExpiry = edit("15", number = true)
        root.addView(etExpiry)

        swDetails = Switch(this)
        swDetails.text = "Pokaż sprzedawcę i kwotę w pierwszym SMS-ie do planistów"
        root.addView(swDetails)

        swNotify = Switch(this)
        swNotify.text = "Informuj pozostałych, kto przejął kod (dodatkowe SMS-y)"
        root.addView(swNotify)

        root.addView(button("Zapisz ustawienia") { save() })
        root.addView(button("Test: udaj SMS z banku") { confirmSimulation() })

        root.addView(label("Dziennik zdarzeń (najnowsze na górze)", bold = true))
        tvLog = TextView(this)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.textSize = 11f
        tvLog.setTextIsSelectable(true)
        root.addView(tvLog)
        root.addView(button("Odśwież dziennik") { refreshLog() })
        root.addView(button("Wyczyść dziennik") {
            AppLog.clear(this)
            refreshLog()
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun loadFields() {
        swEnabled.isChecked = prefs.enabled
        swEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            refreshStatus()
        }
        etBank.setText(prefs.bankSenders)
        etPlanners.setText(prefs.plannersText)
        etExpiry.setText(prefs.expiryMinutes.toString())
        swDetails.isChecked = prefs.showDetails
        swNotify.isChecked = prefs.notifyOthers
        showParsed(prefs.plannersText)
    }

    // ------------------------------------------------------------- akcje

    private fun save() {
        val exp = etExpiry.text.toString().trim().toIntOrNull()
        if (exp == null || exp < 0 || exp > 1440) {
            toast("Ważność kodu: liczba minut od 0 do 1440")
            return
        }
        prefs.bankSenders = etBank.text.toString().trim()
        prefs.plannersText = etPlanners.text.toString()
        prefs.expiryMinutes = exp
        prefs.showDetails = swDetails.isChecked
        prefs.notifyOthers = swNotify.isChecked
        showParsed(prefs.plannersText)
        refreshStatus()
        toast("Zapisano")
    }

    private fun showParsed(text: String) {
        val parsed = Planners.parse(text)
        val nonEmpty = text.lines().count { it.isNotBlank() }
        val sb = StringBuilder()
        if (parsed.isEmpty()) {
            sb.append("Nie rozpoznano żadnego planisty.")
        } else {
            sb.append("Rozpoznani planiści:")
            for (p in parsed) sb.append("\n• ").append(p.name).append(" — ").append(p.number)
        }
        if (nonEmpty > parsed.size) {
            sb.append("\nPominięto ").append(nonEmpty - parsed.size).append(" linii (zły numer lub duplikat).")
        }
        tvParsed.text = sb.toString()
    }

    private fun hasSmsPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun askBattery() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Oszczędzanie baterii jest już wyłączone dla tej aplikacji")
                return
            }
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Otwórz ustawienia baterii ręcznie i wyłącz optymalizację dla tej aplikacji")
            }
        }
    }

    private fun confirmSimulation() {
        if (!hasSmsPermissions()) {
            toast("Najpierw nadaj uprawnienia SMS")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Test")
            .setMessage("Aplikacja udaje SMS z banku i wyśle PRAWDZIWE SMS-y do wszystkich planistów z zapisanej listy. Kontynuować?")
            .setPositiveButton("Wyślij") { _, _ -> runSimulation() }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun runSimulation() {
        val appCtx = applicationContext
        val sender = prefs.bankSenders.split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "TEST"
        val op = Random.nextInt(900, 1000)
        val code = Random.nextInt(100000, 1000000)
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())
        val body = "Operacja nr: $op z dn. $date\nCard payments\nnr karty: TEST\nsprzedawca : TEST SKLEP\nkwota : 1,00 PLN\nHasłoSMS: $code"
        Thread {
            synchronized(Engine.LOCK) {
                Engine(prefs.config(), AndroidEnv(appCtx, prefs)).onSms(sender, body)
            }
            runOnUiThread { refreshLog() }
        }.start()
        toast("Wysłano test – numer operacji $op")
    }

    // ----------------------------------------------------------- odświeżanie

    private fun refreshStatus() {
        val perms = hasSmsPermissions()
        val battery = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }
        val planners = prefs.config().planners.size
        tvStatus.text =
            "Uprawnienia SMS: " + (if (perms) "OK" else "BRAK – dotknij „Nadaj uprawnienia SMS”") + "\n" +
                "Oszczędzanie baterii: " + (if (battery) "wyłączone (dobrze)" else "włączone – może blokować działanie") + "\n" +
                "Planistów na liście: $planners\n" +
                "Stan: " + (if (prefs.enabled) "WŁĄCZONA" else "WYŁĄCZONA")
    }

    private fun refreshLog() {
        val t = AppLog.tail(this, 120)
        tvLog.text = if (t.isEmpty()) "(brak wpisów)" else t
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
