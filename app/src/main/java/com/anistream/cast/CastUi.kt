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

/** Lists the Chromecasts / Google TVs on the Wi-Fi; tap one to connect. When connected, offers Stop casting. */
@Composable
fun CastDevicePicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val device by CastController.device.collectAsState()

    // Android 17 asks before an app talks to devices on the local network.
    val needsPermission = Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
    var permissionAsked by remember { mutableStateOf(!needsPermission) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionAsked = true }
    LaunchedEffect(Unit) { if (needsPermission) askPermission.launch(LOCAL_NETWORK) }

    if (device != null) {
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
    var connecting by remember { mutableStateOf<String?>(null) }

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
    }
    // Close the picker as soon as the TV connects.
    LaunchedEffect(device) { if (device != null && connecting != null) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Cast, null) },
        title = { Text("Cast to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (routes.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Looking for TVs on this Wi-Fi…")
                    }
                }
                routes.forEach { route ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                connecting = route.name
                                route.select()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Tv, null)
                        Spacer(Modifier.width(16.dp))
                        Text(route.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (connecting == route.name) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

