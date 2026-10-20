package com.signalm1

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)

object Binance {
    private val hosts = listOf("https://api.binance.com", "https://data-api.binance.vision")

    fun klines(symbol: String, interval: String, limit: Int, endTime: Long? = null): List<Candle> {
        var last: Exception? = null
        for (h in hosts) {
            try {
                val con = URL("$h/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit" +
                    (if (endTime != null) "&endTime=$endTime" else ""))
                    .openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                val txt = con.inputStream.bufferedReader().use { it.readText() }
                val a = JSONArray(txt)
                return List(a.length()) { i ->
                    val k = a.getJSONArray(i)
                    Candle(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(),
                        k.getString(3).toDouble(), k.getString(4).toDouble())
                }
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("Tsy mahazo data")
    }
}

fun ema(v: List<Double>, p: Int): List<Double> {
    val k = 2.0 / (p + 1)
    val r = ArrayList<Double>(v.size)
    var e = v[0]
    for (x in v) { e = x * k + e * (1 - k); r.add(e) }
    return r
}

fun atr(c: List<Candle>, p: Int = 14): Double {
    var a = 0.0
    for (i in 1 until c.size) {
        val tr = maxOf(c[i].h - c[i].l, abs(c[i].h - c[i - 1].c), abs(c[i].l - c[i - 1].c))
        a = if (i <= p) a + tr / p else (a * (p - 1) + tr) / p
    }
    return a
}

fun adx(c: List<Candle>, p: Int = 14): Double {
    var trS = 0.0; var pS = 0.0; var mS = 0.0; var adx = 0.0
    for (i in 1 until c.size) {
        val up = c[i].h - c[i - 1].h
        val dn = c[i - 1].l - c[i].l
        val pdm = if (up > dn && up > 0) up else 0.0
        val mdm = if (dn > up && dn > 0) dn else 0.0
        val tr = maxOf(c[i].h - c[i].l, abs(c[i].h - c[i - 1].c), abs(c[i].l - c[i - 1].c))
        if (i <= p) { trS += tr; pS += pdm; mS += mdm }
        else { trS = trS - trS / p + tr; pS = pS - pS / p + pdm; mS = mS - mS / p + mdm }
        if (i >= p && trS > 0) {
            val pdi = 100 * pS / trS
            val mdi = 100 * mS / trS
            val dx = if (pdi + mdi == 0.0) 0.0 else 100 * abs(pdi - mdi) / (pdi + mdi)
            adx = if (i < 2 * p) adx + dx / p else (adx * (p - 1) + dx) / p
        }
    }
    return adx
}

object TwelveData {
    fun candles(symbol: String, interval: String, size: Int, key: String, endMs: Long? = null): List<Candle> {
        val sym = symbol.replace("/", "%2F")
        val endPart = if (endMs == null) "" else {
            val ef = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            ef.timeZone = TimeZone.getTimeZone("UTC")
            "&end_date=" + java.net.URLEncoder.encode(ef.format(java.util.Date(endMs)), "UTF-8")
        }
        val con = URL("https://api.twelvedata.com/time_series?symbol=$sym&interval=$interval" +
            "&outputsize=$size&timezone=UTC&apikey=$key" + endPart).openConnection() as HttpURLConnection
        con.connectTimeout = 10000
        con.readTimeout = 10000
        val stream = if (con.responseCode in 200..299) con.inputStream else con.errorStream
        val o = JSONObject(stream.bufferedReader().use { it.readText() })
        if (o.optString("status") == "error") throw Exception(o.optString("message"))
        val v = o.getJSONArray("values")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val list = List(v.length()) { i ->
            val k = v.getJSONObject(i)
            Candle(fmt.parse(k.getString("datetime"))!!.time, k.getString("open").toDouble(),
                k.getString("high").toDouble(), k.getString("low").toDouble(), k.getString("close").toDouble())
        }
        return list.reversed()
    }
}

fun rsi(c: List<Candle>, p: Int = 14): Double {
    if (c.size < p + 2) return 50.0
    var g = 0.0; var l = 0.0
    for (i in 1..p) { val d = c[i].c - c[i - 1].c; if (d > 0) g += d else l -= d }
    g /= p; l /= p
    for (i in p + 1 until c.size) {
        val d = c[i].c - c[i - 1].c
        g = (g * (p - 1) + maxOf(d, 0.0)) / p
        l = (l * (p - 1) + maxOf(-d, 0.0)) / p
    }
    return if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
}

fun swingHighs(c: List<Candle>, k: Int = 2): List<Double> {
    val r = ArrayList<Double>()
    for (i in k until c.size - k) {
        var ok = true
        for (j in 1..k) if (c[i].h <= c[i - j].h || c[i].h <= c[i + j].h) ok = false
        if (ok) r.add(c[i].h)
    }
    return r
}

fun swingLows(c: List<Candle>, k: Int = 2): List<Double> {
    val r = ArrayList<Double>()
    for (i in k until c.size - k) {
        var ok = true
        for (j in 1..k) if (c[i].l >= c[i - j].l || c[i].l >= c[i + j].l) ok = false
        if (ok) r.add(c[i].l)
    }
    return r
}

fun trAvg(c: List<Candle>, n: Int = 200): Double {
    val s = c.takeLast(n + 1)
    if (s.size < 2) return 0.0
    var t = 0.0
    for (i in 1 until s.size)
        t += maxOf(s[i].h - s[i].l, abs(s[i].h - s[i - 1].c), abs(s[i].l - s[i - 1].c))
    return t / (s.size - 1)
}

class NewsEv(val t: Long, val ccy: String, val title: String)

object News {
    fun fetch(): List<NewsEv> {
        val con = URL("https://nfs.faireconomy.media/ff_calendar_thisweek.json")
            .openConnection() as HttpURLConnection
        con.connectTimeout = 10000
        con.readTimeout = 10000
        val a = JSONArray(con.inputStream.bufferedReader().use { it.readText() })
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val r = ArrayList<NewsEv>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("impact") != "High") continue
            val d = try { fmt.parse(o.getString("date"))!!.time } catch (e: Exception) { continue }
            r.add(NewsEv(d, o.optString("country"), o.optString("title")))
        }
        return r
    }
}

fun binanceHistory(symbol: String, interval: String, total: Int): List<Candle> {
    val all = ArrayList<Candle>()
    var end: Long? = null
    while (all.size < total) {
        val page = Binance.klines(symbol, interval, 1000, end)
        if (page.isEmpty()) break
        all.addAll(page)
        end = page.first().t - 1
        if (page.size < 1000) break
    }
    return all.distinctBy { it.t }.sortedBy { it.t }.takeLast(total)
}

fun tdHistory(symbol: String, interval: String, total: Int, key: String): List<Candle> {
    val all = ArrayList<Candle>()
    var end: Long? = null
    while (all.size < total) {
        val want = minOf(5000, total - all.size)
        val page = TwelveData.candles(symbol, interval, want, key, end)
        if (page.isEmpty()) break
        val oldest = all.minOfOrNull { it.t } ?: Long.MAX_VALUE
        if (page.first().t >= oldest) break
        all.addAll(page)
        end = page.first().t - 1000
        if (page.size < want) break
        Thread.sleep(1500)
    }
    return all.distinctBy { it.t }.sortedBy { it.t }.takeLast(total)
}
