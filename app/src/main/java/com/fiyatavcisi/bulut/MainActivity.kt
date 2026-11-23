package com.fiyatavcisi.bulut

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.fiyatavcisi.bulut.ui.CloudAppScreen
import com.fiyatavcisi.bulut.ui.CloudViewModel
import com.fiyatavcisi.bulut.worker.CloudSyncWorker

private val BulutDarkColors = darkColorScheme(
    primary = Color(0xFF0EA5E9),
    secondary = Color(0xFF10B981),
    background = Color(0xFF0B1220),
    surface = Color(0xFF162032),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC)
)

class MainActivity : ComponentActivity() {

    private val viewModel: CloudViewModel by viewModels()

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloudSyncWorker.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = BulutDarkColors) {
                CloudAppScreen(viewModel = viewModel)
            }
        }
    }
}
