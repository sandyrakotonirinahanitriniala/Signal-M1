package com.signalm1

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

const val RR = 1.5

class Settings(
    var strict: Boolean = true,
    var spread: Double = 0.0,
    var session: Boolean = true,
    var newsOn: Boolean = true,
    var maxSignals: Int = 6
)

class Sig(val type: String, val entry: Double, val sl: Double, val tp: Double,
          val lot: Double, val t0: Long, val spread: Double) {
    var status = "PENDING"
}

class Engine {
    var active: Sig? = null
    var wins = 0
    var losses = 0
    var rSum = 0.0
    var streak = 0
    var pauseUntil = 0L
    var lastSigT = 0L
    var info = ""
    var dec = 2
    var clock = 0L
    val results = ArrayList<Double>()
    var cancelled = 0
    var generated = 0
    private var day = -1L
    private var sigToday = 0
    private var winToday = 0
    private var lossToday = 0
    private var maxSig = 6

    private fun f(x: Double) = String.format(Locale.US, "%.${dec}f", x)
    private fun f2(x: Double) = String.format(Locale.US, "%.2f", x)

    fun describe(s: Sig) =
        "${s.type}\nPE : ${f(s.entry)}\nSL : ${f(s.sl)}\nTP : ${f(s.tp)}\nLot : ${f2(s.lot)} (risque 1%)"

    private fun finish(win: Boolean, ev: MutableList<String>) {
        active = null
        if (win) {
            wins++; winToday++; rSum += RR; streak = 0; results.add(RR)
            ev.add("🎯 TP tratra (+${RR}R). Mamoaka signal manaraka...")
        } else {
            losses++; lossToday++; rSum -= 1; streak++; results.add(-1.0)
            ev.add("🛑 SL tratra (-1R).")
            if (streak >= 2) {
                pauseUntil = clock + 15 * 60000
                streak = 0
                ev.add("⏸ Fiatoana 15 min aorian'ny 2 SL")
            }
        }
    }

    // Buy: miditra amin'ny Ask (bid + spread). Sell: miditra amin'ny Bid.
    private fun evaluate(s: Sig, m1: List<Candle>, ev: MutableList<String>) {
        val buy = s.type.startsWith("BUY")
        val sp = s.spread
        for (c in m1) {
            if (c.t <= s.t0) continue
            if (s.status == "PENDING") {
                if ((c.t - s.t0) / 60000 >= 10) {
                    active = null; cancelled++
                    ev.add("⌛ Signal lany daty: tsy tratra ny limit tao anatin'ny 10 min. Ario ao MT5 raha mbola tsy tratra."); return
                }
                val tpFirst = if (buy) c.h >= s.tp && c.l + sp > s.entry else c.l + sp <= s.tp && c.h < s.entry
                if (tpFirst) { active = null; cancelled++; ev.add("❌ Signal najanona: tratra ny TP nefa tsy niditra ny limit"); return }
                val filled = if (buy) c.l + sp <= s.entry else c.h >= s.entry
                if (filled) {
                    s.status = "OPEN"
                    ev.add("✅ Limit tratra: position nisokatra")
                    val slHit = if (buy) c.l <= s.sl else c.h + sp >= s.sl
                    if (slHit) { finish(false, ev); return }
                    continue
                }
            } else {
                val slHit = if (buy) c.l <= s.sl else c.h + sp >= s.sl
                val tpHit = if (buy) c.h >= s.tp else c.l + sp <= s.tp
                if (slHit) { finish(false, ev); return }
                if (tpHit) { finish(true, ev); return }
            }
        }
    }

    private fun generate(c1: List<Candle>, c5: List<Candle>, c15: List<Candle>, bal: Double,
                         contract: Double, st: Settings, news: List<NewsEv>,
                         ccy: Set<String>, nowMs: Long): Sig? {
        if (c1.size < 120 || c5.size < 60 || c15.size < 60) { info = "Data tsy ampy"; return null }
        val price = c1.last().c
        val a = atr(c1)
        val ad = adx(c1)
        info = "Prix ${f(price)} | ADX ${f2(ad)} | ATR ${f(a)}"
        fun no(m: String): Sig? { info += "\n⛔ $m"; return null }

        if (st.session) {
            val hr = ((nowMs / 3600000L) % 24).toInt()
            if (hr < 7 || hr >= 21) return no("Ivelan'ny session Londres/NY (07-21h UTC)")
        }
        if (st.newsOn) {
            for (n in news) if (n.ccy in ccy && abs(nowMs - n.t) <= 30 * 60000L)
                return no("News mahery: ${n.ccy} ${n.title}")
        }
        if (sigToday >= st.maxSignals) return no("Fetra signal isan'andro tratra (${st.maxSignals})")
        if (lossToday >= 3) return no("3 SL androany: fiatoana hatramin'ny rahampitso")

        val e20 = ema(c1.map { it.c }, 20).last()
        val e50 = ema(c1.map { it.c }, 50).last()
        val m20 = ema(c5.map { it.c }, 20).last()
        val m50 = ema(c5.map { it.c }, 50).last()
        val q20 = ema(c15.map { it.c }, 20).last()
        val q50 = ema(c15.map { it.c }, 50).last()
        if (ad < 20) return no("ADX ambany: marché miovaova")
        val up = e20 > e50 && m20 > m50 && (!st.strict || q20 > q50)
        val down = e20 < e50 && m20 < m50 && (!st.strict || q20 < q50)
        if (!up && !down) return no("Tsy mifanaraka ny trend (M1/M5${if (st.strict) "/M15" else ""})")
        val buy = up
        if (buy && price <= e20) return no("Prix ambanin'ny EMA20")
        if (!buy && price >= e20) return no("Prix ambonin'ny EMA20")

        val slD = 1.5 * a
        val entry = if (buy) minOf(e20, price - 0.1 * a) else maxOf(e20, price + 0.1 * a)
        val sl = if (buy) entry - slD else entry + slD
        val tp = if (buy) entry + slD * RR else entry - slD * RR
        if (st.spread > 0.2 * slD) return no("Spread be loatra (${f(st.spread)} > 20% an'ny SL)")

        if (st.strict) {
            if (ad <= adx(c1.dropLast(3))) return no("ADX tsy miakatra (trend tsy mihamafy)")
            val sh = swingHighs(c5.takeLast(120))
            val sw = swingLows(c5.takeLast(120))
            if (sh.size < 2 || sw.size < 2) return no("Structure tsy mazava")
            if (buy && !(sh.last() > sh[sh.size - 2] && sw.last() > sw[sw.size - 2])) return no("Tsy HH/HL")
            if (!buy && !(sh.last() < sh[sh.size - 2] && sw.last() < sw[sw.size - 2])) return no("Tsy LH/LL")
            val r = rsi(c1)
            if (r < 40 || r > 60) return no("RSI ${f2(r)} (tokony 40-60)")
            val last = c1.last()
            if (buy && !(last.c > last.o && last.c > e20)) return no("Tsy misy bougie fanamafisana (manga)")
            if (!buy && !(last.c < last.o && last.c < e20)) return no("Tsy misy bougie fanamafisana (mena)")
            if (buy && sh.any { it > entry && it < tp }) return no("Résistance akaikin'ny TP")
            if (!buy && sw.any { it < entry && it > tp }) return no("Support akaikin'ny TP")
            val ta = trAvg(c1)
            if (ta > 0 && (a < 0.5 * ta || a > 2.0 * ta)) return no("ATR tsy ara-dalàna (marché matory na tafahoatra)")
        }
        val lot = maxOf(0.01, floor((bal * 0.01 / (slD * contract)) / 0.01) * 0.01)
        return Sig(if (buy) "BUY LIMIT" else "SELL LIMIT", entry, sl, tp, lot, c1.last().t, st.spread)
    }

    fun tick(m1: List<Candle>, m5: List<Candle>, m15: List<Candle>, bal: Double, contract: Double,
             st: Settings, news: List<NewsEv>, ccy: Set<String>,
             nowMs: Long = System.currentTimeMillis()): List<String> {
        val ev = mutableListOf<String>()
        val now = nowMs
        clock = nowMs
        val d = now / 86400000L
        if (d != day) { day = d; sigToday = 0; winToday = 0; lossToday = 0 }
        maxSig = st.maxSignals
        active?.let { evaluate(it, m1, ev) }
        if (now - m1.last().t > 180000) {
            info = "Marché mikatona na tsy vaovao ny data (Prix ${f(m1.last().c)})"
            return ev
        }
        if (active == null && now >= pauseUntil) {
            val s = generate(m1.dropLast(1), m5.dropLast(1), m15.dropLast(1), bal, contract, st, news, ccy, now)
            if (s != null && s.t0 != lastSigT) {
                active = s; lastSigT = s.t0; sigToday++; generated++
                ev.add("📢 SIGNAL VAOVAO\n" + describe(s))
            }
        } else if (active != null) {
            info = "Prix ${f(m1.last().c)}"
        } else {
            info = "Prix ${f(m1.last().c)}\n⏸ Fiatoana mandra"
        }
        return ev
    }

    fun stats(): String {
        val n = wins + losses
        val wr = if (n > 0) 100.0 * wins / n else 0.0
        return "ANDROANY: signal $sigToday/$maxSig | TP $winToday | SL $lossToday\n" +
            "REHETRA: trade $n | TP $wins | SL $losses | Win ${f2(wr)}% | Total ${f2(rSum)}R"
    }
}
