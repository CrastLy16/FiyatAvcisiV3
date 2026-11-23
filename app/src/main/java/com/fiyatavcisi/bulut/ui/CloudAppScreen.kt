package com.fiyatavcisi.bulut.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fiyatavcisi.bulut.model.CloudDeal
import com.fiyatavcisi.bulut.model.CloudOffer
import com.fiyatavcisi.bulut.model.CloudProduct
import com.fiyatavcisi.bulut.model.MatchStatus
import com.fiyatavcisi.bulut.model.StoreSite
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAppScreen(viewModel: CloudViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(if (state.settings.snapshotRawUrl.isBlank()) 2 else 0) }
    var showAddProduct by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var addOfferProductId by remember { mutableStateOf<String?>(null) }

    val tl = remember { NumberFormat.getCurrencyInstance(Locale("tr", "TR")) }
    val dateFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale("tr", "TR")) }

    val openUrl: (String) -> Unit = { url ->
        if (url.startsWith("http")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    val shareDeal: (String, String) -> Unit = { title, url ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        context.startActivity(Intent.createChooser(send, "İndirim Linkini Paylaş"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fiyat Avcısı Bulut", fontWeight = FontWeight.Bold)
                        val sub = when {
                            state.statusText.isNotBlank() -> state.statusText
                            state.snapshot.updatedAt > 0L -> "Son bulut taraması: ${dateFmt.format(Date(state.snapshot.updatedAt))}"
                            else -> "7 sitede 0-pil bulut takip"
                        }
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncNow() }, enabled = !state.isBusy) {
                        if (state.isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Yenile")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            when (tab) {
                0 -> FloatingActionButton(onClick = { showAddProduct = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Ürün Ekle")
                }
                1 -> FloatingActionButton(onClick = { showAddCategory = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Kategori Ekle")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.LocalOffer, contentDescription = null) },
                    label = { Text("Ürünler (${state.snapshot.products.size})") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text("Fırsatlar (${state.snapshot.bigDeals.size})") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Bulut & Ayarlar") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> CloudProductsTab(
                    products = state.snapshot.products,
                    enabledSites = state.settings.enabledSites,
                    tl = tl,
                    onOpenUrl = openUrl,
                    onAddOffer = { addOfferProductId = it },
                    onDelete = { viewModel.deleteProduct(it) }
                )
                1 -> CloudDealsTab(
                    deals = state.snapshot.bigDeals.filter {
                        (it.site == StoreSite.OTHER || it.site in state.settings.enabledSites) &&
                            it.dropPercent >= state.settings.minDropPercent &&
                            ((it.isFromCategory && state.settings.notifyAnyCategoryDeals) ||
                                (!it.isFromCategory && state.settings.notifyTrackedProducts))
                    },
                    tl = tl,
                    onOpenUrl = openUrl,
                    onShare = { context.startActivity(Intent.createChooser(it, "İndirim Linkini Paylaş")) }
                )
                2 -> CloudSettingsTab(state = state, viewModel = viewModel)
            }
        }
    }

    if (showAddProduct) {
        AddProductDialog(
            onDismiss = { showAddProduct = false },
            onConfirm = { name, pct, urls ->
                viewModel.addProduct(name, pct, urls)
                showAddProduct = false
            }
        )
    }

    if (showAddCategory) {
        AddCategoryDialog(
            onDismiss = { showAddCategory = false },
            onConfirm = { name, url, pct ->
                viewModel.addCategory(name, url, pct)
                showAddCategory = false
            }
        )
    }

    addOfferProductId?.let { pid ->
        AddOfferLinkDialog(
            onDismiss = { addOfferProductId = null },
            onConfirm = { url ->
                viewModel.addOfferLink(pid, url)
                addOfferProductId = null
            }
        )
    }
}

@Composable
private fun CloudProductsTab(
    products: List<CloudProduct>,
    enabledSites: Set<StoreSite>,
    tl: NumberFormat,
    onOpenUrl: (String) -> Unit,
    onAddOffer: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (products.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sağ alttaki + butonuna basarak doğrudan ürün linki ekleyebilirsin. (Uygulamadan eklemek için Bulut & Ayarlar sekmesine 1 kez GitHub Token girmen yeterli.)",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(products, key = { it.id }) { prod ->
            val visibleOffers = prod.offers.filter { it.site == StoreSite.OTHER || it.site in enabledSites }
            val cheapest = visibleOffers.mapNotNull { it.price }.minOrNull()

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(prod.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Bildirim eşiği: %${prod.targetDropPercent}+ düşüş",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { onDelete(prod.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Sil")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    visibleOffers.sortedBy { it.price ?: Double.MAX_VALUE }.forEach { offer ->
                        CloudOfferRow(
                            offer = offer,
                            isCheapest = cheapest != null && offer.price == cheapest,
                            tl = tl,
                            onOpen = { onOpenUrl(offer.url) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    OutlinedButton(onClick = { onAddOffer(prod.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Başka Siteden Link Ekle")
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudOfferRow(
    offer: CloudOffer,
    isCheapest: Boolean,
    tl: NumberFormat,
    onOpen: () -> Unit
) {
    val bg = if (isCheapest) Color(0xFF064E3B).copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(offer.site.displayName, fontWeight = FontWeight.SemiBold)
                if (isCheapest) {
                    Text("EN UCUZ", style = MaterialTheme.typography.labelSmall, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                }
            }
            if (offer.title.isNotBlank()) {
                Text(
                    text = offer.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val color = when (offer.matchStatus) {
                    MatchStatus.NEEDS_REVIEW -> Color(0xFFF59E0B)
                    MatchStatus.UNCHECKED -> Color(0xFF94A3B8)
                    else -> Color(0xFF34D399)
                }
                Icon(
                    imageVector = if (offer.matchStatus == MatchStatus.NEEDS_REVIEW) Icons.Default.Warning else Icons.Default.Verified,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "${offer.matchStatus.label}${if (offer.matchScore > 0) " (%${offer.matchScore})" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = offer.price?.let { tl.format(it) } ?: offer.status,
                fontWeight = FontWeight.Bold,
                color = if (offer.price != null) MaterialTheme.colorScheme.onSurface else Color(0xFFFCA5A5)
            )
            if (offer.dropPercent > 0.1) {
                Text(
                    text = "-%${String.format(Locale("tr", "TR"), "%.1f", offer.dropPercent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        IconButton(onClick = onOpen) {
            Icon(Icons.Default.OpenInBrowser, contentDescription = "Ürünü Aç")
        }
    }
}

@Composable
private fun CloudDealsTab(
    deals: List<CloudDeal>,
    tl: NumberFormat,
    onOpenUrl: (String) -> Unit,
    onShare: (Intent) -> Unit
) {
    if (deals.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Bulut tarayıcısı 7 sitenin fırsat sayfalarını 30 dakikada bir tarar. Büyük indirim yakalandığında burada görünür ve telefonuna bildirim gelir. Özel kategori eklemek için sağ alttaki + butonunu kullanabilirsin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(deals, key = { it.id }) { deal ->
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "-%${String.format(Locale("tr", "TR"), "%.1f", deal.dropPercent)} • ${deal.site.displayName}",
                        color = Color(0xFF34D399),
                        fontWeight = FontWeight.Bold
                    )
                    Text(deal.title, fontWeight = FontWeight.SemiBold)
                    Text("${tl.format(deal.oldPrice)} → ${tl.format(deal.newPrice)}")
                    if (deal.comparisonSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = deal.comparisonSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenUrl(deal.url) }) { Text("Satın Al (Uygulamada Aç)") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${deal.title} (-%${String.format(Locale("tr", "TR"), "%.1f", deal.dropPercent)})\n${deal.url}")
                            }
                            onShare(send)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Paylaş", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudSettingsTab(state: CloudUiState, viewModel: CloudViewModel) {
    val s = state.settings
    var ghUser by remember(s.githubUsername) { mutableStateOf(s.githubUsername) }
    var ghRepo by remember(s.githubRepo) { mutableStateOf(s.githubRepo) }
    var ghToken by remember(s.githubToken) { mutableStateOf(s.githubToken) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GitHub Bulut Bağlantısı", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = ghUser,
                        onValueChange = { ghUser = it },
                        label = { Text("GitHub Kullanıcı Adın") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ghRepo,
                        onValueChange = { ghRepo = it },
                        label = { Text("Depo Adı") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ghToken,
                        onValueChange = { ghToken = it },
                        label = { Text("GitHub Token (Uygulamadan + ile eklemek için)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Button(
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(githubUsername = ghUser, githubRepo = ghRepo, githubToken = ghToken)
                            }
                            viewModel.syncNow()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Kaydet ve Buluta Bağlan") }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bildirim ve Tarama Tercihleri", fontWeight = FontWeight.Bold)
                    SettingSwitch("7 Sitede Genel Büyük İndirim Taraması", s.scanGeneralDealsInCloud) { v ->
                        viewModel.updateSettings(syncCloudFlags = true) { it.copy(scanGeneralDealsInCloud = v) }
                    }
                    SettingSwitch("Seçili ürün fiyat düşüşü bildirimleri", s.notifyTrackedProducts) { v ->
                        viewModel.updateSettings { it.copy(notifyTrackedProducts = v) }
                    }
                    SettingSwitch("Genel / Kategori büyük indirim bildirimleri", s.notifyAnyCategoryDeals) { v ->
                        viewModel.updateSettings { it.copy(notifyAnyCategoryDeals = v) }
                    }
                    SettingSwitch("Arka plan bildirim kontrolü", s.backgroundSyncEnabled) { v ->
                        viewModel.updateSettings { it.copy(backgroundSyncEnabled = v) }
                    }
                    Text("Minimum indirim bildirimi eşiği: %${s.minDropPercent}+", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = s.minDropPercent.toFloat(),
                        onValueChange = { v -> viewModel.updateSettings(syncCloudFlags = true) { it.copy(minDropPercent = v.toInt()) } },
                        valueRange = 5f..70f
                    )
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aktif Siteler (7 Site)", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoreSite.entries.filter { it != StoreSite.OTHER }.forEach { site ->
                            FilterChip(
                                selected = site in s.enabledSites,
                                onClick = {
                                    viewModel.updateSettings(syncCloudFlags = true) { st ->
                                        val next = st.enabledSites.toMutableSet()
                                        if (!next.add(site)) next.remove(site)
                                        st.copy(enabledSites = next)
                                    }
                                },
                                label = { Text(site.displayName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AddProductDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf("15") }
    var urlsText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ürün Ekle (Link Yapıştır)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ürün Adı (örn. iPhone 16)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Bildirim için min. düşüş (%)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = urlsText,
                    onValueChange = { urlsText = it },
                    label = { Text("Ürün linkleri (her satıra 1 site linki)") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(name, percent.toIntOrNull() ?: 15, urlsText.lines())
            }) { Text("Buluta Ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}

@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf("20") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Özel Kategori / Arama Ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Kategori Adı (örn. Robot Süpürge)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Kategori / Arama Sayfası Linki") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Min. indirim oranı (%)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, url, percent.toIntOrNull() ?: 20) }) { Text("Ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}

@Composable
private fun AddOfferLinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Başka Siteden Link Ekle") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Ürün linkini yapıştır") }
            )
        },
        confirmButton = { Button(onClick = { onConfirm(url) }) { Text("Ekle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}
