package com.fiyatavcisi.bulut.model

enum class StoreSite(val displayName: String, val domainHint: String) {
    AMAZON("Amazon TR", "amazon.com.tr"),
    HEPSIBURADA("Hepsiburada", "hepsiburada.com"),
    N11("N11", "n11.com"),
    TRENDYOL("Trendyol", "trendyol.com"),
    VATAN("Vatan", "vatanbilgisayar.com"),
    TEKNOSA("Teknosa", "teknosa.com"),
    MEDIAMARKT("MediaMarkt", "mediamarkt.com.tr"),
    OTHER("Diğer", "");

    companion object {
        fun fromCode(code: String): StoreSite {
            return runCatching { valueOf(code.uppercase()) }.getOrDefault(OTHER)
        }

        fun detectFromUrl(url: String): StoreSite {
            val lower = url.lowercase()
            return entries.firstOrNull { it != OTHER && lower.contains(it.domainHint) } ?: OTHER
        }
    }
}

enum class MatchStatus(val label: String) {
    VERIFIED_BARCODE("Barkod/GTIN Doğrulandı"),
    VERIFIED_MODEL("Model Kodu Eşleşti"),
    VERIFIED_HIGH("Aynı Ürün Doğrulandı"),
    NEEDS_REVIEW("Farklı Ürün Olabilir"),
    REFERENCE("Referans Ürün"),
    UNCHECKED("Doğrulanmadı");

    companion object {
        fun fromCode(code: String): MatchStatus {
            return runCatching { valueOf(code.uppercase()) }.getOrDefault(UNCHECKED)
        }
    }
}

data class CloudOffer(
    val site: StoreSite,
    val url: String,
    val title: String = "",
    val price: Double? = null,
    val previousPrice: Double? = null,
    val dropPercent: Double = 0.0,
    val status: String = "Tarama bekleniyor",
    val matchStatus: MatchStatus = MatchStatus.UNCHECKED,
    val matchScore: Int = 0
)

data class CloudProduct(
    val id: String,
    val name: String,
    val targetDropPercent: Int,
    val offers: List<CloudOffer>
)

data class CloudCategory(
    val id: String,
    val name: String,
    val site: StoreSite,
    val url: String,
    val minDropPercent: Int,
    val scannedCount: Int = 0,
    val status: String = "Tarama bekleniyor"
)

data class CloudDeal(
    val id: String,
    val title: String,
    val site: StoreSite,
    val url: String,
    val oldPrice: Double,
    val newPrice: Double,
    val dropPercent: Double,
    val comparisonSummary: String,
    val isFromCategory: Boolean
)

data class CloudSnapshot(
    val updatedAt: Long = 0L,
    val products: List<CloudProduct> = emptyList(),
    val categories: List<CloudCategory> = emptyList(),
    val bigDeals: List<CloudDeal> = emptyList()
)

data class CloudSettings(
    val githubUsername: String = "",
    val githubRepo: String = "",
    val githubToken: String = "",
    val branch: String = "main",
    val notifyTrackedProducts: Boolean = true,
    val notifyAnyCategoryDeals: Boolean = true,
    val scanGeneralDealsInCloud: Boolean = true,
    val minDropPercent: Int = 20,
    val syncIntervalMinutes: Long = 30L,
    val backgroundSyncEnabled: Boolean = true,
    val enabledSites: Set<StoreSite> = StoreSite.entries.filter { it != StoreSite.OTHER }.toSet()
) {
    val snapshotRawUrl: String
        get() = if (githubUsername.isNotBlank() && githubRepo.isNotBlank()) {
            "https://raw.githubusercontent.com/${githubUsername.trim()}/${githubRepo.trim()}/${branch.trim().ifBlank { "main" }}/cloud_monitor/deals_snapshot.json"
        } else {
            ""
        }

    val watchlistApiUrl: String
        get() = if (githubUsername.isNotBlank() && githubRepo.isNotBlank()) {
            "https://api.github.com/repos/${githubUsername.trim()}/${githubRepo.trim()}/contents/cloud_monitor/watchlist.json"
        } else {
            ""
        }
}
