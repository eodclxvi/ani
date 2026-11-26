package com.anistream.cast

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent

/** The Cast icon: outline when idle, filled when a TV is connected. Hidden where Cast isn't available. */
@Composable
fun CastButton(tint: Color = LocalContentColor.current) {
    val available by CastController.available.collectAsState()
    val device by CastController.device.collectAsState()
    if (!available) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            if (device != null) Icons.Default.CastConnected else Icons.Default.Cast,
            contentDescription = if (device != null) "Casting to $device" else "Cast to a TV",
            tint = if (device != null) MaterialTheme.colorScheme.primary else tint,
        )
    }
    if (open) CastDevicePicker(onDismiss = { open = false })
}

/**
 * Lists the TVs on the Wi-Fi: Chromecasts / Google TVs, LG webOS TVs and other DLNA TVs. Tap one to connect.
 * When connected, offers Stop casting.
 */
@Composable
fun CastDevicePicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val device by CastController.device.collectAsState()
    val scope = rememberCoroutineScope()

    // Android 17 asks before an app talks to devices on the local network.
    val needsPermission = Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
    var permissionAsked by remember { mutableStateOf(!needsPermission) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionAsked = true }
    LaunchedEffect(Unit) { if (needsPermission) askPermission.launch(LOCAL_NETWORK) }

    var connecting by remember { mutableStateOf<String?>(null) }
    var lgPrompt by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (device != null && connecting == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.CastConnected, null) },
            title = { Text("Casting to $device") },
            confirmButton = { TextButton(onClick = { CastController.disconnect(); onDismiss() }) { Text("Stop casting") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember {
        CastController.context?.mergedSelector ?: MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }
    var routes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    var lanTvs by remember { mutableStateOf(emptyList<LanTv>()) }
    var scanning by remember { mutableStateOf(true) }

    if (permissionAsked) {
        DisposableEffect(router) {
            fun refresh() {
                routes = router.routes.filter {
                    !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) &&
                        // Only things with a screen: skip Nest speakers and speaker groups.
                        CastDevice.getFromBundle(it.extras)?.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) == true
                }
            }
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            }
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            refresh()
            onDispose { router.removeCallback(callback) }
        }
        // LG and DLNA TVs: ask the network twice, since a TV just switched on can be slow to answer.
        LaunchedEffect(Unit) {
            repeat(2) {
                val found = LanDiscovery.scan(context)
                lanTvs = (lanTvs + found).distinctBy { it.ip }.sortedBy { it.name.lowercase() }
            }
            scanning = false
        }
    }
    // A Chromecast connected: close the picker.
    LaunchedEffect(device) { if (device != null && connecting != null) onDismiss() }

    // Chromecasts often also answer as DLNA TVs; list each TV once, as a Chromecast.
    @Suppress("DEPRECATION")
    val castIps = routes.mapNotNull { CastDevice.getFromBundle(it.extras)?.inetAddress?.hostAddress }.toSet()
    val others = lanTvs.filter { it.ip !in castIps }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Cast, null) },
        title = { Text("Cast to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                routes.forEach { route ->
                    DeviceRow(route.name, busy = connecting == route.name, enabled = connecting == null) {
                        connecting = route.name
                        error = null
                        route.select()
                    }
                }
                others.forEach { tv ->
                    DeviceRow(tv.name, busy = connecting == tv.name, enabled = connecting == null) {
                        connecting = tv.name
                        error = null
                        scope.launch {
                            val result = CastController.connectLan(context, tv) { lgPrompt = true }
                            lgPrompt = false
                            if (result == null) onDismiss() else { error = result; connecting = null }
                        }
                    }
                }
                if (lgPrompt) {
                    Text(
                        "Your TV is asking whether to allow this device. Choose Allow with the TV remote.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (scanning || (routes.isEmpty() && others.isEmpty())) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            if (scanning) "Looking for TVs on this Wi-Fi…"
                            else "No TVs found. Check the TV is on and on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeviceRow(name: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Tv, null)
        Spacer(Modifier.width(16.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

