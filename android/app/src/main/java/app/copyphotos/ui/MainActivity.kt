package app.copyphotos.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.copyphotos.CopyPhotosApp
import app.copyphotos.R
import app.copyphotos.media.libraryAccess

class MainActivity : ComponentActivity() {
    private val viewModels get() = (application as CopyPhotosApp).graph.viewModels
    private val photos: PhotosViewModel by viewModels { viewModels }
    private val destination: DestinationViewModel by viewModels { viewModels }
    private val details: PhotoDetailsViewModel by viewModels { viewModels }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(photos, destination, details, onPermissionsChanged = { updateAccess(resumed = false) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccess(resumed = true)
    }

    private fun updateAccess(resumed: Boolean) {
        photos.onAccessChanged(libraryAccess(this), resumed)
    }
}

@Composable
fun App(
    photos: PhotosViewModel,
    destination: DestinationViewModel,
    details: PhotoDetailsViewModel,
    onPermissionsChanged: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, null) }, label = { Text(stringResource(R.string.tab_photos)) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Storage, null) }, label = { Text(stringResource(R.string.tab_destination)) })
            }
        }
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (tab) {
            0 -> PhotosScreen(photos, details, onPermissionsChanged, mod)
            else -> DestinationScreen(destination, mod)
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
