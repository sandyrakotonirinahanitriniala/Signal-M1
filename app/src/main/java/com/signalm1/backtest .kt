package com.signalm1

import java.util.Locale

object Backtest {
    private fun f(x: Double) = String.format(Locale.US, "%.2f", x)

    fun run(m1: List<Candle>, m5: List<Candle>, m15: List<Candle>, bal: Double,
            mk: Mkt, st: Settings): String {
        if (m1.size < 400) return "Data tsy ampy ho an'ny backtest (${m1.size} candles)."
        val eng = Engine().also { it.dec = mk.dec }
        var p5 = 0
        var p15 = 0
        for (i in 150 until m1.size - 1) {
            val closeT = m1[i].t + 60000
            while (p5 < m5.size && m5[p5].t + 300000 <= closeT) p5++
            while (p15 < m15.size && m15[p15].t + 900000 <= closeT) p15++
            if (p5 < 61 || p15 < 61) continue
            val w1 = ArrayList<Candle>(m1.subList(maxOf(0, i - 298), i + 1))
            val w5 = ArrayList<Candle>(m5.subList(maxOf(0, p5 - 100), p5))
            val w15 = ArrayList<Candle>(m15.subList(maxOf(0, p15 - 100), p15))
            // bougie dummy (mbola mihetsika) mba tsy hisy "lookahead"
            for (w in listOf(w1, w5, w15)) {
                val l = w.last()
                w.add(Candle(l.t + 60000, l.c, l.c, l.c, l.c))
            }
            eng.tick(w1, w5, w15, bal, mk.contract, st, emptyList(), mk.ccy, closeT + 10000)
        }
        val r = eng.results
        val n = r.size
        val days = (m1.last().t - m1[150].t) / 86400000.0
        if (n == 0) return "Tsy nisy trade tratra tao anatin'ny ~${f(days)} andro (signal: ${eng.generated}). " +
            "Mety strict loatra ny conditions, na vitsy loatra ny data."
        val w = r.count { it > 0 }
        val l = n - w
        val pos = r.filter { it > 0 }.sum()
        val neg = -r.filter { it < 0 }.sum()
        val pf = if (neg == 0.0) 99.0 else pos / neg
        val total = r.sum()
        var eq = 0.0; var peak = 0.0; var dd = 0.0
        for (x in r) { eq += x; if (eq > peak) peak = eq; if (peak - eq > dd) dd = peak - eq }
        val wr = 100.0 * w / n
        fun part(a: List<Double>): String {
            if (a.isEmpty()) return "-"
            return "trade ${a.size} | win ${f(100.0 * a.count { it > 0 } / a.size)}% | ${f(a.sum())}R"
        }
        val first = r.subList(0, n / 2)
        val second = r.subList(n / 2, n)
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        return "BACKTEST ${mk.name} | ~${f(days)} andro | ${if (st.strict) "strict" else "tsotra"}\n" +
            "Solde ${f(bal)} | risque 1%/trade | spread ${st.spread} | TP ${RR}R\n\n" +
            "Trade: $n | TP $w | SL $l | Win ${f(wr)}%\n" +
            "Profit factor: ${f(pf)}\n" +
            "Total: ${f(total)}R = ${f(total * bal * 0.01)} USD\n" +
            "Drawdown max: ${f(dd)}R (~${f(dd)}% an'ny solde)\n" +
            "Signal: ${eng.generated} | tsy tratra/lany daty: ${eng.cancelled}\n\n" +
            "Antsasa-potoana 1: ${part(first)}\nAntsasa-potoana 2: ${part(second)}\n\n" +
            "${mark(n >= 200)} Trade ≥ 200 ($n)\n" +
            "${mark(pf > 1.3)} Profit factor > 1.3\n" +
            "${mark(dd < 15)} Drawdown < 15%\n" +
            "${mark(wr >= 40)} Win rate ≥ 40% (zava-dehibe ho an'ny RR 1.5)\n" +
            "${mark(first.sum() > 0 && second.sum() > 0)} Tsara ny antsasa-potoana roa\n\n" +
            "Fanamarihana: tsy ao ny news (tsy misy tantara), slippage ary commission. " +
            "Raha latsaky ny 200 ny trade, tsy azo tompoina ny valiny: ohatra kely ihany."
    }
}
