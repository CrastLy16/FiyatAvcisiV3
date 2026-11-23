package com.fiyatavcisi.bulut.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fiyatavcisi.bulut.data.CloudRepository
import com.fiyatavcisi.bulut.model.CloudSettings
import com.fiyatavcisi.bulut.model.CloudSnapshot
import com.fiyatavcisi.bulut.worker.CloudSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudUiState(
    val settings: CloudSettings = CloudSettings(),
    val snapshot: CloudSnapshot = CloudSnapshot(),
    val isBusy: Boolean = false,
    val statusText: String = ""
)

class CloudViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = CloudRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(
        CloudUiState(
            settings = repo.loadSettings(),
            snapshot = repo.loadCachedSnapshot()
        )
    )
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        CloudSyncWorker.schedule(application.applicationContext, _uiState.value.settings)
        if (_uiState.value.settings.snapshotRawUrl.isNotBlank()) {
            syncNow()
        }
    }

    fun updateSettings(syncCloudFlags: Boolean = false, transform: (CloudSettings) -> CloudSettings) {
        val next = transform(_uiState.value.settings)
        repo.saveSettings(next)
        CloudSyncWorker.schedule(getApplication(), next)
        _uiState.update { it.copy(settings = next) }
        if (syncCloudFlags && next.githubToken.isNotBlank()) {
            viewModelScope.launch {
                repo.updateWatchlistOnGitHub(next, "Bulut tarama ayarları güncellendi") { }
            }
        }
    }

    fun syncNow() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Bulut verisi yenileniyor...") }
            val result = repo.fetchLatestSnapshot(_uiState.value.settings)
            result.onSuccess { snap ->
                _uiState.update {
                    it.copy(
                        snapshot = snap,
                        isBusy = false,
                        statusText = "Güncel: ${snap.products.size} ürün, ${snap.bigDeals.size} büyük indirim"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isBusy = false, statusText = err.message ?: "Bulut bağlantısı başarısız")
                }
            }
        }
    }

    fun addProduct(name: String, targetDropPercent: Int, urls: List<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Ürün buluta kaydediliyor...") }
            val res = repo.addProductViaApi(_uiState.value.settings, name, targetDropPercent, urls)
            res.onSuccess {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusText = "Ürün eklendi! Bulut taraması başladı (2-3 dk içinde fiyatlar düşer)."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isBusy = false, statusText = err.message ?: "Eklenemedi") }
            }
        }
    }

    fun addOfferLink(productId: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Karşılaştırma linki ekleniyor...") }
            val res = repo.addOfferToProductViaApi(_uiState.value.settings, productId, url)
            res.onSuccess {
                _uiState.update {
                    it.copy(isBusy = false, statusText = "Link eklendi! Bulut yeni siteyi tarıyor.")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isBusy = false, statusText = err.message ?: "Eklenemedi") }
            }
        }
    }

    fun deleteProduct(productId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Ürün siliniyor...") }
            val res = repo.deleteProductViaApi(_uiState.value.settings, productId)
            res.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusText = "Ürün takipten çıkarıldı.") }
            }.onFailure { err ->
                _uiState.update { it.copy(isBusy = false, statusText = err.message ?: "Silinemedi") }
            }
        }
    }

    fun addCategory(name: String, url: String, minDropPercent: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusText = "Kategori buluta ekleniyor...") }
            val res = repo.addCategoryViaApi(_uiState.value.settings, name, url, minDropPercent)
            res.onSuccess {
                _uiState.update {
                    it.copy(isBusy = false, statusText = "Kategori eklendi! Bulut taraması başladı.")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isBusy = false, statusText = err.message ?: "Eklenemedi") }
            }
        }
    }
}
