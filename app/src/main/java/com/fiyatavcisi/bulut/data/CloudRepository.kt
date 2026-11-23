package com.fiyatavcisi.bulut.data

import android.content.Context
import android.util.Base64
import com.fiyatavcisi.bulut.model.CloudCategory
import com.fiyatavcisi.bulut.model.CloudDeal
import com.fiyatavcisi.bulut.model.CloudOffer
import com.fiyatavcisi.bulut.model.CloudProduct
import com.fiyatavcisi.bulut.model.CloudSettings
import com.fiyatavcisi.bulut.model.CloudSnapshot
import com.fiyatavcisi.bulut.model.MatchStatus
import com.fiyatavcisi.bulut.model.StoreSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class CloudRepository(context: Context) {

    private val prefs = context.getSharedPreferences("fiyat_avcisi_bulut_v2_prefs", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "cloud_snapshot_v2_cache.json")

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun loadSettings(): CloudSettings {
        val rawSites = prefs.getStringSet("enabled_sites", null)
        val enabled = rawSites?.map { StoreSite.fromCode(it) }?.toSet()
            ?: StoreSite.entries.filter { it != StoreSite.OTHER }.toSet()
        return CloudSettings(
            githubUsername = prefs.getString("gh_user", "").orEmpty(),
            githubRepo = prefs.getString("gh_repo", "").orEmpty(),
            githubToken = prefs.getString("gh_token", "").orEmpty(),
            branch = prefs.getString("gh_branch", "main").orEmpty(),
            notifyTrackedProducts = prefs.getBoolean("notify_products", true),
            notifyAnyCategoryDeals = prefs.getBoolean("notify_categories", true),
            scanGeneralDealsInCloud = prefs.getBoolean("scan_general", true),
            minDropPercent = prefs.getInt("min_drop_pct", 20),
            syncIntervalMinutes = prefs.getLong("sync_interval", 30L),
            backgroundSyncEnabled = prefs.getBoolean("bg_sync", true),
            enabledSites = enabled
        )
    }

    fun saveSettings(s: CloudSettings) {
        prefs.edit()
            .putString("gh_user", s.githubUsername.trim())
            .putString("gh_repo", s.githubRepo.trim())
            .putString("gh_token", s.githubToken.trim())
            .putString("gh_branch", s.branch.trim().ifBlank { "main" })
            .putBoolean("notify_products", s.notifyTrackedProducts)
            .putBoolean("notify_categories", s.notifyAnyCategoryDeals)
            .putBoolean("scan_general", s.scanGeneralDealsInCloud)
            .putInt("min_drop_pct", s.minDropPercent)
            .putLong("sync_interval", s.syncIntervalMinutes)
            .putBoolean("bg_sync", s.backgroundSyncEnabled)
            .putStringSet("enabled_sites", s.enabledSites.map { it.name }.toSet())
            .apply()
    }

    fun getNotifiedDealKeys(): Set<String> = prefs.getStringSet("notified_keys", emptySet()) ?: emptySet()

    fun markDealsNotified(keys: Set<String>) {
        val merged = (getNotifiedDealKeys() + keys).toList().takeLast(250).toSet()
        prefs.edit().putStringSet("notified_keys", merged).apply()
    }

    fun loadCachedSnapshot(): CloudSnapshot {
        if (!cacheFile.exists()) return CloudSnapshot()
        return runCatching { parseSnapshot(cacheFile.readText(Charsets.UTF_8)) }.getOrDefault(CloudSnapshot())
    }

    suspend fun fetchLatestSnapshot(settings: CloudSettings): Result<CloudSnapshot> = withContext(Dispatchers.IO) {
        val url = settings.snapshotRawUrl
        if (url.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Ayarlar sekmesinden GitHub kullanıcı adı ve depo adını girin."))
        }
        try {
            val request = Request.Builder()
                .url("$url?t=${System.currentTimeMillis()}")
                .header("User-Agent", "FiyatAvcisiBulut/2.1")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Bulut verisi alınamadı (HTTP ${resp.code}). Depo Public olmalı.")
                    )
                }
                val raw = resp.body?.string().orEmpty()
                val snapshot = parseSnapshot(raw)
                cacheFile.writeText(raw, Charsets.UTF_8)
                Result.success(snapshot)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uygulama icinden dogrudan GitHub cloud_monitor/watchlist.json dosyasini gunceller (JSON elle duzenlemeye gerek kalmaz).
     */
    suspend fun updateWatchlistOnGitHub(
        settings: CloudSettings,
        commitMessage: String,
        mutator: (JSONObject) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val apiUrl = settings.watchlistApiUrl
        if (apiUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Önce Bulut & Ayarlar sekmesinden kullanıcı adı ve depo adını girin."))
        }
        if (settings.githubToken.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Uygulamadan ürün eklemek için Bulut & Ayarlar sekmesine GitHub Token girin."))
        }

        try {
            // 1) Mevcut watchlist.json ve sha degerini oku
            val getReq = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer ${settings.githubToken.trim()}")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FiyatAvcisiBulut/2.1")
                .get()
                .build()

            var sha: String? = null
            var watchlistJson = JSONObject().apply {
                put("globalMinDropPercent", settings.minDropPercent)
                put("scanGeneralDeals", settings.scanGeneralDealsInCloud)
                put("enabledSites", JSONArray(settings.enabledSites.map { it.name }))
                put("products", JSONArray())
                put("categories", JSONArray())
            }

            client.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    sha = obj.optString("sha").takeIf { it.isNotBlank() }
                    val encoded = obj.optString("content").replace("\n", "")
                    if (encoded.isNotBlank()) {
                        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                        watchlistJson = JSONObject(decoded)
                    }
                } else if (resp.code == 401 || resp.code == 403) {
                    return@withContext Result.failure(
                        IllegalStateException("GitHub Token yetkisiz (HTTP ${resp.code}). Token'a Contents: Read and write izni verin.")
                    )
                }
            }

            // 2) Degisikligi uygula
            watchlistJson.put("globalMinDropPercent", settings.minDropPercent)
            watchlistJson.put("scanGeneralDeals", settings.scanGeneralDealsInCloud)
            watchlistJson.put("enabledSites", JSONArray(settings.enabledSites.map { it.name }))
            mutator(watchlistJson)

            val pretty = watchlistJson.toString(2)
            val base64Content = Base64.encodeToString(pretty.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val putPayload = JSONObject().apply {
                put("message", commitMessage)
                put("content", base64Content)
                put("branch", settings.branch.trim().ifBlank { "main" })
                if (sha != null) put("sha", sha)
            }

            val putReq = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer ${settings.githubToken.trim()}")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FiyatAvcisiBulut/2.1")
                .put(putPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(putReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("GitHub kaydı başarısız (HTTP ${resp.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addProductViaApi(
        settings: CloudSettings,
        name: String,
        targetDropPercent: Int,
        urls: List<String>
    ): Result<Unit> {
        val cleanUrls = urls.map { it.trim() }.filter { it.startsWith("http") }.distinct()
        if (name.isBlank() || cleanUrls.isEmpty()) {
            return Result.failure(IllegalStateException("Ürün adı ve en az 1 geçerli link girin."))
        }
        return updateWatchlistOnGitHub(settings, "Uygulamadan ürün eklendi: $name") { root ->
            val products = root.optJSONArray("products") ?: JSONArray().also { root.put("products", it) }
            val offersArr = JSONArray()
            for (u in cleanUrls) {
                offersArr.put(
                    JSONObject().apply {
                        put("site", StoreSite.detectFromUrl(u).name)
                        put("url", u)
                    }
                )
            }
            products.put(
                JSONObject().apply {
                    put("id", "p_${UUID.randomUUID().toString().take(8)}")
                    put("name", name.trim())
                    put("targetDropPercent", targetDropPercent.coerceIn(1, 90))
                    put("offers", offersArr)
                }
            )
        }
    }

    suspend fun addOfferToProductViaApi(
        settings: CloudSettings,
        productId: String,
        url: String
    ): Result<Unit> {
        val clean = url.trim()
        if (!clean.startsWith("http")) {
            return Result.failure(IllegalStateException("Geçerli bir ürün linki girin."))
        }
        return updateWatchlistOnGitHub(settings, "Ürüne karşılaştırma linki eklendi") { root ->
            val products = root.optJSONArray("products") ?: return@updateWatchlistOnGitHub
            for (i in 0 until products.length()) {
                val p = products.optJSONObject(i) ?: continue
                if (p.optString("id") == productId) {
                    val offers = p.optJSONArray("offers") ?: JSONArray().also { p.put("offers", it) }
                    offers.put(
                        JSONObject().apply {
                            put("site", StoreSite.detectFromUrl(clean).name)
                            put("url", clean)
                        }
                    )
                    break
                }
            }
        }
    }

    suspend fun deleteProductViaApi(settings: CloudSettings, productId: String): Result<Unit> {
        return updateWatchlistOnGitHub(settings, "Ürün takipten çıkarıldı") { root ->
            val oldArr = root.optJSONArray("products") ?: return@updateWatchlistOnGitHub
            val next = JSONArray()
            for (i in 0 until oldArr.length()) {
                val p = oldArr.optJSONObject(i) ?: continue
                if (p.optString("id") != productId) next.put(p)
            }
            root.put("products", next)
        }
    }

    suspend fun addCategoryViaApi(
        settings: CloudSettings,
        name: String,
        url: String,
        minDropPercent: Int
    ): Result<Unit> {
        val clean = url.trim()
        if (name.isBlank() || !clean.startsWith("http")) {
            return Result.failure(IllegalStateException("Kategori adı ve geçerli link girin."))
        }
        return updateWatchlistOnGitHub(settings, "Kategori eklendi: $name") { root ->
            val cats = root.optJSONArray("categories") ?: JSONArray().also { root.put("categories", it) }
            cats.put(
                JSONObject().apply {
                    put("id", "c_${UUID.randomUUID().toString().take(8)}")
                    put("name", name.trim())
                    put("site", StoreSite.detectFromUrl(clean).name)
                    put("url", clean)
                    put("minDropPercent", minDropPercent.coerceIn(1, 90))
                }
            )
        }
    }

    private fun parseSnapshot(raw: String): CloudSnapshot {
        val root = JSONObject(raw)
        val updatedAt = root.optLong("updatedAt", 0L)

        val products = mutableListOf<CloudProduct>()
        val prodArr = root.optJSONArray("products") ?: JSONArray()
        for (i in 0 until prodArr.length()) {
            val p = prodArr.optJSONObject(i) ?: continue
            val offers = mutableListOf<CloudOffer>()
            val offArr = p.optJSONArray("offers") ?: JSONArray()
            for (j in 0 until offArr.length()) {
                val o = offArr.optJSONObject(j) ?: continue
                offers.add(
                    CloudOffer(
                        site = StoreSite.fromCode(o.optString("site")),
                        url = o.optString("url"),
                        title = o.optString("title"),
                        price = if (!o.isNull("price")) o.optDouble("price") else null,
                        previousPrice = if (!o.isNull("previousPrice")) o.optDouble("previousPrice") else null,
                        dropPercent = o.optDouble("dropPercent", 0.0),
                        status = o.optString("status"),
                        matchStatus = MatchStatus.fromCode(o.optString("matchStatus")),
                        matchScore = o.optInt("matchScore", 0)
                    )
                )
            }
            products.add(
                CloudProduct(
                    id = p.optString("id", "p_$i"),
                    name = p.optString("name", "İsimsiz Ürün"),
                    targetDropPercent = p.optInt("targetDropPercent", 15),
                    offers = offers
                )
            )
        }

        val categories = mutableListOf<CloudCategory>()
        val catArr = root.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until catArr.length()) {
            val c = catArr.optJSONObject(i) ?: continue
            categories.add(
                CloudCategory(
                    id = c.optString("id", "c_$i"),
                    name = c.optString("name", "Kategori"),
                    site = StoreSite.fromCode(c.optString("site")),
                    url = c.optString("url"),
                    minDropPercent = c.optInt("minDropPercent", 20),
                    scannedCount = c.optInt("scannedCount", 0),
                    status = c.optString("status")
                )
            )
        }

        val deals = mutableListOf<CloudDeal>()
        val dealArr = root.optJSONArray("bigDeals") ?: JSONArray()
        for (i in 0 until dealArr.length()) {
            val d = dealArr.optJSONObject(i) ?: continue
            val url = d.optString("url")
            val newPrice = d.optDouble("newPrice", 0.0)
            deals.add(
                CloudDeal(
                    id = "${url}_${newPrice}",
                    title = d.optString("title", "Büyük İndirim"),
                    site = StoreSite.fromCode(d.optString("site")),
                    url = url,
                    oldPrice = d.optDouble("oldPrice", 0.0),
                    newPrice = newPrice,
                    dropPercent = d.optDouble("dropPercent", 0.0),
                    comparisonSummary = d.optString("comparisonSummary"),
                    isFromCategory = d.optBoolean("isFromCategory", false)
                )
            )
        }

        return CloudSnapshot(updatedAt, products, categories, deals)
    }
}
