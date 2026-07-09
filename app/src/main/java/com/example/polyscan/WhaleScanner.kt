package com.example.polyscan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Config {
    const val MIN_USD = 10_000.0
    const val PAGE_LIMIT = 1000
    const val MAX_OFFSET = 10_000
    const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000   // 3 Tage
    const val INITIAL_LOOKBACK_SEC = 3600L              // Kaltstart: 1 h zurueck
    const val DATA_API = "https://data-api.polymarket.com/trades"
}

data class WhaleSettings(
    val dolphinMin: Double = 10_000.0,
    val whaleMin: Double = 50_000.0,
    val megalodonMin: Double = 200_000.0,
    val dolphinActive: Boolean = true,
    val whaleActive: Boolean = true,
    val megalodonActive: Boolean = true,
)

object SettingsStore {
    private const val PREFS = "polyscan_settings"

    fun load(ctx: Context): WhaleSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WhaleSettings(
            dolphinMin = p.getFloat("dolphin_min", 10_000f).toDouble(),
            whaleMin = p.getFloat("whale_min", 50_000f).toDouble(),
            megalodonMin = p.getFloat("megalodon_min", 200_000f).toDouble(),
            dolphinActive = p.getBoolean("dolphin_active", true),
            whaleActive = p.getBoolean("whale_active", true),
            megalodonActive = p.getBoolean("megalodon_active", true),
        )
    }

    fun save(ctx: Context, s: WhaleSettings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putFloat("dolphin_min", s.dolphinMin.toFloat())
            putFloat("whale_min", s.whaleMin.toFloat())
            putFloat("megalodon_min", s.megalodonMin.toFloat())
            putBoolean("dolphin_active", s.dolphinActive)
            putBoolean("whale_active", s.whaleActive)
            putBoolean("megalodon_active", s.megalodonActive)
            apply()
        }
    }
}

// ---------------------------------------------------------------------------
// Datenmodell
// ---------------------------------------------------------------------------
data class Whale(
    val key: String,
    val timestampMs: Long,
    val title: String,
    val usd: Double,
    val category: String,
    val side: String,
    val outcome: String,        // worauf gewettet wurde (z. B. "Yes", "No", Team-Name)
    val outcomeIndex: Int,      // 0 = Yes, 1 = No (Ersatz, falls outcome leer ist)
    val price: Double,
    val size: Double,
    val wallet: String,
    val slug: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("timestampMs", timestampMs); put("title", title)
        put("usd", usd); put("category", category); put("side", side)
        put("outcome", outcome); put("outcomeIndex", outcomeIndex)
        put("price", price); put("size", size); put("wallet", wallet); put("slug", slug)
    }

    companion object {
        fun fromJson(o: JSONObject) = Whale(
            key = o.getString("key"),
            timestampMs = o.getLong("timestampMs"),
            title = o.optString("title"),
            usd = o.getDouble("usd"),
            category = o.optString("category"),
            side = o.optString("side"),
            outcome = o.optString("outcome"),
            outcomeIndex = o.optInt("outcomeIndex", -1),
            price = o.optDouble("price"),
            size = o.optDouble("size"),
            wallet = o.optString("wallet"),
            slug = o.optString("slug"),
        )
    }
}

fun categorize(usd: Double, settings: WhaleSettings): String = when {
    usd >= settings.megalodonMin -> "Megalodon"
    usd >= settings.whaleMin -> "Whale"
    usd >= settings.dolphinMin -> "Dolphin"
    else -> "Unter Schwelle"
}

private fun usdValue(t: JSONObject): Double {
    for (f in listOf("usdcSize", "usdValue")) {
        if (t.has(f) && !t.isNull(f)) {
            val v = t.optDouble(f, Double.NaN)
            if (!v.isNaN()) return v
        }
    }
    return t.optDouble("size", 0.0) * t.optDouble("price", 0.0)
}

private fun tradeKey(t: JSONObject): String {
    val tx = t.optString("transactionHash", "")
    val asset = t.optString("asset", "")
    return if (tx.isNotEmpty()) "$tx:$asset"
    else "${t.optString("proxyWallet")}:${t.optString("timestamp")}:$asset"
}

private fun timestampMs(t: JSONObject): Long {
    val sec = t.optLong("timestamp", 0L)
    return if (sec > 0) sec * 1000L else System.currentTimeMillis()
}

// ---------------------------------------------------------------------------
// API-Client
// ---------------------------------------------------------------------------
object PolymarketClient {
    fun fetchPage(minUsd: Double, limit: Int, offset: Int): JSONArray {
        val url = Config.DATA_API +
                "?filterType=CASH" +
                "&filterAmount=" + minUsd.toInt() +
                "&limit=" + limit +
                "&offset=" + offset +
                "&takerOnly=true"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "polyscan-android/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) return JSONArray()
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            return if (body.startsWith("[")) JSONArray(body)
            else JSONObject(body).optJSONArray("trades") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------------------
// Lokale Speicherung
// ---------------------------------------------------------------------------
object WhaleStore {
    private const val PREFS = "polyscan_prefs"
    private const val KEY_WATERMARK = "watermark_sec"
    private const val FILE = "whales.json"

    fun loadWhales(ctx: Context): MutableList<Whale> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { Whale.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveWhales(ctx: Context, whales: List<Whale>) {
        val arr = JSONArray()
        whales.forEach { arr.put(it.toJson()) }
        File(ctx.filesDir, FILE).writeText(arr.toString())
    }

    fun getWatermarkSec(ctx: Context): Long {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_WATERMARK, 0L)
        return if (saved > 0) saved
        else System.currentTimeMillis() / 1000L - Config.INITIAL_LOOKBACK_SEC
    }

    fun setWatermarkSec(ctx: Context, sec: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_WATERMARK, sec).apply()
    }
}

// ---------------------------------------------------------------------------
// Hintergrund-Worker
// ---------------------------------------------------------------------------
class WhaleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        try {
            val settings = SettingsStore.load(ctx)
            val watermarkSec = WhaleStore.getWatermarkSec(ctx)
            val stored = WhaleStore.loadWhales(ctx)
            val knownKeys = stored.map { it.key }.toHashSet()

            val newWhales = mutableListOf<Whale>()
            var maxSec = watermarkSec
            var offset = 0

            while (offset <= Config.MAX_OFFSET) {
                val page = PolymarketClient.fetchPage(Config.MIN_USD, Config.PAGE_LIMIT, offset)
                if (page.length() == 0) break

                var pageOldestSec = Long.MAX_VALUE
                for (i in 0 until page.length()) {
                    val t = page.getJSONObject(i)
                    val sec = t.optLong("timestamp", 0L)
                    if (sec in 1 until pageOldestSec) pageOldestSec = sec

                    val key = tradeKey(t)
                    if (knownKeys.contains(key)) continue
                    val usd = usdValue(t)
                    if (usd < Config.MIN_USD) continue

                    newWhales.add(
                        Whale(
                            key = key,
                            timestampMs = timestampMs(t),
                            title = t.optString("title", "Unbekannter Markt").take(80),
                            usd = usd,
                            category = categorize(usd, settings),
                            side = t.optString("side", ""),
                            outcome = t.optString("outcome", ""),
                            outcomeIndex = t.optInt("outcomeIndex", -1),
                            price = t.optDouble("price", 0.0),
                            size = t.optDouble("size", 0.0),
                            wallet = t.optString("proxyWallet", ""),
                            slug = t.optString("slug", ""),
                        )
                    )
                    knownKeys.add(key)
                    if (sec > maxSec) maxSec = sec
                }

                if (pageOldestSec != Long.MAX_VALUE && pageOldestSec < watermarkSec) { break }
                offset += Config.PAGE_LIMIT
            }

            val cutoff = System.currentTimeMillis() - Config.RETENTION_MS
            val merged = (newWhales + stored)
                .filter { it.timestampMs >= cutoff }
                .distinctBy { it.key }
                .sortedByDescending { it.timestampMs }

            WhaleStore.saveWhales(ctx, merged)
            if (newWhales.isNotEmpty()) {
                WhaleStore.setWatermarkSec(ctx, maxSec)
                WhaleNotifications.notifyNew(ctx, newWhales, settings)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// ---------------------------------------------------------------------------
// Benachrichtigungen – NUR bei Megalodons
// ---------------------------------------------------------------------------
object WhaleNotifications {
    private const val CHANNEL_ID = "whale_alerts"

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Megalodon-Trades", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Meldung bei sehr grossen Polymarket-Trades (Megalodon)" }
            )
        }
    }

    fun notifyNew(ctx: Context, whales: List<Whale>, settings: WhaleSettings) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val megs = whales.filter { it.category == "Megalodon" && settings.megalodonActive }
        if (megs.isEmpty()) return
        val top = megs.maxByOrNull { it.usd } ?: return
        val text = if (megs.size == 1)
            "Megalodon: ${"%,.0f".format(top.usd)} \$ – ${top.title}"
        else
            "${megs.size} neue Megalodons · groesster ${"%,.0f".format(top.usd)} \$"

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Neuer Megalodon-Trade")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(ctx)
            .notify(System.currentTimeMillis().toInt(), notification)
    }
}
