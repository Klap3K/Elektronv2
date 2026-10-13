package pl.zse.bydgoszcz.elektron.presentation.transit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import pl.zse.bydgoszcz.elektron.presentation.common.SafeUrls
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import pl.zse.bydgoszcz.elektron.BuildConfig
import pl.zse.bydgoszcz.elektron.domain.model.SchoolTransit
import pl.zse.bydgoszcz.elektron.domain.model.TransitDestination
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransitMap(stops: List<TransitDestination>, destination: TransitDestination?, saving: Boolean, onSelect: (TransitDestination) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var selected by remember { mutableStateOf<TransitDestination?>(null) }
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSaving by rememberUpdatedState(saving)
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val titleMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.35f).coerceAtMost(160f).dp
    val stopOverlay = remember(density.density, density.fontScale) {
        TransitStopOverlay(density.density, 12 * density.density * density.fontScale) {
            if (!latestSaving) {
                focus.clearFocus(); keyboard?.hide()
                selected = it
            }
        }
    }
    val map = remember(context, owner) {
        Configuration.getInstance().apply {
            userAgentValue = "Elektron-Vulcan/${BuildConfig.VERSION_NAME} (+https://github.com/Klap3K/Elektronv2)"
            osmdroidBasePath = File(context.cacheDir, "transit_map")
            osmdroidTileCache = File(osmdroidBasePath, "tiles").apply { mkdirs() }
            tileFileSystemCacheMaxBytes = 30L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 25L * 1024 * 1024
            expirationExtendedDuration = TimeUnit.DAYS.toMillis(7)
            tileDownloadThreads = 2
        }
        MapView(context).apply {
            setTileSource(XYTileSource("OpenStreetMap", 0, 19, 256, ".png",
                arrayOf("https://tile.openstreetmap.org/"), "© OpenStreetMap contributors"))
            setDestroyMode(false) // Compose owns cleanup; avoid a second detach from the window.
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 10.0; maxZoomLevel = 18.0
            controller.setZoom(14.0)
            controller.setCenter(destination?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(SchoolTransit.LATITUDE, SchoolTransit.LONGITUDE))
        }
    }
    DisposableEffect(map, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { map.setUseDataConnection(true); map.onResume(); map.invalidate() }
            if (event == Lifecycle.Event.ON_PAUSE) { map.setUseDataConnection(false); map.onPause() }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.setUseDataConnection(false); map.onPause(); map.onDetach() }
    }
    LaunchedEffect(stops) {
        if (stops.none { it.key == selected?.key }) selected = null
    }
    DisposableEffect(map, stopOverlay) {
        map.overlays.add(stopOverlay)
        onDispose { map.overlays.remove(stopOverlay) }
    }
    LaunchedEffect(map, stopOverlay, stops, selected?.key, colors.primary, colors.onPrimary, colors.surface, colors.onSurface) {
        stopOverlay.stops = stops
        stopOverlay.selectedKey = selected?.key
        stopOverlay.bottomInset = 0f
        stopOverlay.primary = colors.primary.toArgb()
        stopOverlay.foreground = colors.onPrimary.toArgb()
        stopOverlay.surface = colors.surface.toArgb()
        stopOverlay.textColor = colors.onSurface.toArgb()
        map.invalidate()
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp) {
            IconButton(onClick = {
                map.controller.animateTo(GeoPoint(SchoolTransit.LATITUDE, SchoolTransit.LONGITUDE))
            }) { Icon(Icons.Filled.School, "Szkoła") }
        }
        Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp) {
            Column {
                IconButton(onClick = { map.controller.zoomIn() }) { Icon(Icons.Filled.Add, "Przybliż mapę") }
                HorizontalDivider(Modifier.width(48.dp))
                IconButton(onClick = { map.controller.zoomOut() }) { Icon(Icons.Filled.Remove, "Oddal mapę") }
            }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(4.dp).clickable { SafeUrls.open(context, "https://www.openstreetmap.org/copyright") }, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
        }
        }
    }
    // A separate sheet window is not constrained by the remaining map height.
    // Title scrolls if necessary; Close and Choose always keep their own touch space.
    selected?.let { stop ->
        ModalBottomSheet(onDismissRequest = { if (!latestSaving) selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).heightIn(max = titleMaxHeight).verticalScroll(rememberScrollState())) {
                        Text(stop.name, style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { selected = null }, enabled = !saving) {
                        Icon(Icons.Filled.Close, "Anuluj wybór przystanku")
                    }
                }
                Button(onClick = { latestSelect(stop) }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Wybierz ten przystanek")
                }
            }
        }
    }
}
