package com.signalm1

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

class Mkt(val name: String, val td: String?, val dec: Int, val contract: Double,
          val ccy: Set<String>, val spread: String)

class MainActivity : Activity() {
    private val markets = listOf(
        Mkt("BTCUSDT", null, 2, 1.0, setOf("USD"), "10"),
        Mkt("XAUUSD", "XAU/USD", 2, 100.0, setOf("USD"), "0.30"),
        Mkt("EURUSD", "EUR/USD", 5, 100000.0, setOf("USD", "EUR"), "0.00012")
    )
    private val h = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var engine = Engine()
    private var cur = markets[0]
    private var m5Cache: List<Candle> = emptyList()
    private var m15Cache: List<Candle> = emptyList()
    private var newsCache: List<NewsEv> = emptyList()
    private var lastM5 = 0L
    private var lastM15 = 0L
    private var lastNews = 0L
    private var newsFailed = false
    private var running = false
    private var notifId = 1
    private val log = ArrayList<String>()
    private lateinit var out: TextView
    private lateinit var bal: EditText
    private lateinit var keyEt: EditText
    private lateinit var spreadEt: EditText
    private lateinit var spin: Spinner
    private lateinit var btn: Button
    private lateinit var btBtn: Button
    private lateinit var cbStrict: CheckBox
    private lateinit var cbSession: CheckBox
    private lateinit var cbNews: CheckBox

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val balance = bal.text.toString().toDoubleOrNull() ?: 1000.0
            val mk = cur
            val td = mk.td
            val k = keyEt.text.toString().trim()
            val st = Settings(cbStrict.isChecked, spreadEt.text.toString().toDoubleOrNull() ?: 0.0,
                cbSession.isChecked, cbNews.isChecked, 6)
            if (td != null && k.isEmpty()) {
                out.text = "Ampidiro aloha ny clé API Twelve Data (maimaim-poana: twelvedata.com)."
                toggle(); return
            }
            exec.execute {
                try {
                    val now = System.currentTimeMillis()
                    val m1 = if (td == null) Binance.klines(mk.name, "1m", 300)
                             else TwelveData.candles(td, "1min", 300, k)
                    if (m5Cache.isEmpty() || now - lastM5 > 300000) {
                        m5Cache = if (td == null) Binance.klines(mk.name, "5m", 100)
                                  else TwelveData.candles(td, "5min", 100, k)
                        lastM5 = now
                    }
                    if (m15Cache.isEmpty() || now - lastM15 > 900000) {
                        m15Cache = if (td == null) Binance.klines(mk.name, "15m", 100)
                                   else TwelveData.candles(td, "15min", 100, k)
                        lastM15 = now
                    }
                    if (st.newsOn && now - lastNews > 3600000) {
                        lastNews = now
                        try { newsCache = News.fetch(); newsFailed = false }
                        catch (e: Exception) { newsFailed = true }
                    }
                    val ev = engine.tick(m1, m5Cache, m15Cache, balance, mk.contract, st, newsCache, mk.ccy)
                    runOnUiThread {
                        for (e in ev) { log.add(0, e); notify(e.lines().first(), e) }
                        render()
                    }
                } catch (e: Exception) {
                    runOnUiThread { out.text = "Erreur: ${e.message}\n\n" + out.text }
                }
            }
            h.postDelayed(this, if (td == null) 15000L else 60000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32)
        }
        root.addView(TextView(this).apply { text = "Signal M1 v5"; textSize = 22f })
        spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, markets.map { it.name })
        root.addView(spin)
        root.addView(TextView(this).apply { text = "Solde (USD) ho an'ny lot 1%:" })
        bal = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1000")
        }
        root.addView(bal)
        root.addView(TextView(this).apply { text = "Spread mahazatra an'ny broker (isa amin'ny prix):" })
        spreadEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(markets[0].spread)
        }
        root.addView(spreadEt)
        cbStrict = CheckBox(this).apply { text = "Mode strict (conditions rehetra)"; isChecked = true }
        cbSession = CheckBox(this).apply { text = "Filtre session Londres/NY (07-21h UTC)"; isChecked = false }
        cbNews = CheckBox(this).apply { text = "Filtre news mahery (±30 min)"; isChecked = true }
        root.addView(cbStrict); root.addView(cbSession); root.addView(cbNews)
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                spreadEt.setText(markets[pos].spread)
                cbSession.isChecked = pos != 0
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(TextView(this).apply { text = "Clé API Twelve Data (ho an'ny XAUUSD/EURUSD):" })
        keyEt = EditText(this).apply { setText(prefs.getString("key", "")); setSingleLine() }
        root.addView(keyEt)
        btn = Button(this).apply { text = "Démarrer"; setOnClickListener { toggle() } }
        root.addView(btn)
        btBtn = Button(this).apply { text = "Backtest (data taloha)"; setOnClickListener { runBacktest() } }
        root.addView(btBtn)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 14f
            text = "Safidio ny marché ary tsindrio 'Démarrer'.\nTsy mandefa order ho azy ny application: ianao no mametraka azy ao amin'ny MT5."
        }
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toggle() {
        running = !running
        btn.text = if (running) "Arrêter" else "Démarrer"
        spin.isEnabled = !running
        if (running) {
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("key", keyEt.text.toString().trim()).apply()
            cur = markets[spin.selectedItemPosition]
            engine = Engine().also { it.dec = cur.dec }
            m5Cache = emptyList(); m15Cache = emptyList()
            lastM5 = 0L; lastM15 = 0L; lastNews = 0L; newsFailed = false; log.clear()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            h.post(loop)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            h.removeCallbacks(loop)
        }
    }

    private fun runBacktest() {
        if (running) { out.text = "Atsaharo aloha ny Arrêter/Démarrer."; return }
        val mk = markets[spin.selectedItemPosition]
        val td = mk.td
        val k = keyEt.text.toString().trim()
        if (td != null && k.isEmpty()) {
            out.text = "Ampidiro aloha ny clé API Twelve Data (maimaim-poana: twelvedata.com)."
            return
        }
        val balance = bal.text.toString().toDoubleOrNull() ?: 1000.0
        val st = Settings(cbStrict.isChecked, spreadEt.text.toString().toDoubleOrNull() ?: 0.0,
            cbSession.isChecked, false, 6)
        btBtn.isEnabled = false
        out.text = "Mandray data taloha ho an'ny ${mk.name}... (mety haharitra 10-60 s)"
        exec.execute {
            val res = try {
                val m1 = if (td == null) binanceHistory(mk.name, "1m", 10000) else tdHistory(td, "1min", 15000, k)
                val m5 = if (td == null) binanceHistory(mk.name, "5m", 3000) else tdHistory(td, "5min", 3200, k)
                val m15 = if (td == null) binanceHistory(mk.name, "15m", 1100) else tdHistory(td, "15min", 1100, k)
                runOnUiThread { out.text = "Mandefa ny backtest (${m1.size} candles M1)..." }
                Backtest.run(m1, m5, m15, balance, mk, st)
            } catch (e: Exception) { "Erreur: ${e.message}" }
            runOnUiThread { out.text = res; btBtn.isEnabled = true }
        }
    }

    private fun render() {
        val s = engine.active
        val c = if (s != null) "SIGNAL ACTIF [${s.status}]\n" + engine.describe(s) else "Miandry signal..."
        val nw = if (cbNews.isChecked && newsFailed) "\n⚠️ News tsy azo alaina: tsy mandeha ny filtre news" else ""
        out.text = cur.name + "\n" + engine.info + nw + "\n\n" + c + "\n\n" + engine.stats() +
            "\n\n--- Tantara ---\n" + log.take(20).joinToString("\n\n")
    }

    private fun notify(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("sig", "Signal", NotificationManager.IMPORTANCE_HIGH))
            Notification.Builder(this, "sig")
        } else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(cur.name + ": " + title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
        nm.notify(notifId++, b.build())
    }

    override fun onDestroy() { h.removeCallbacks(loop); super.onDestroy() }
}
