package lt.kantrybes.tvremote.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.R

private const val LOCAL_NETWORK_SDK = 37

internal object LocalNetwork {
    fun isRequired(): Boolean = !BuildConfig.IS_DEMO && Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK

    fun isGranted(context: Context): Boolean {
        if (!isRequired()) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

internal class LocalNetworkAccess(context: Context) {
    var granted by mutableStateOf(LocalNetwork.isGranted(context))
        private set
    var denied by mutableStateOf(false)
        private set
    var showRationale by mutableStateOf(false)
        private set
    var preferSettings by mutableStateOf(false)
        private set

    private var pending: (() -> Unit)? = null

    fun request(onGranted: () -> Unit = {}) {
        if (granted) {
            denied = false
            onGranted()
            return
        }
        pending = onGranted
        showRationale = true
    }

    fun dismissRationale() {
        showRationale = false
        pending = null
        denied = true
    }

    fun beginSystemRequest() {
        showRationale = false
    }

    fun onPermissionResult(grantedNow: Boolean, activity: Activity) {
        granted = grantedNow || !LocalNetwork.isRequired()
        showRationale = false
        if (granted) {
            denied = false
            preferSettings = false
            val action = pending
            pending = null
            action?.invoke()
        } else {
            denied = true
            preferSettings = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            )
            pending = null
        }
    }

    fun refresh(context: Context) {
        val now = LocalNetwork.isGranted(context)
        if (now) {
            granted = true
            denied = false
            preferSettings = false
        } else if (granted) {
            granted = false
            denied = true
        }
    }
}

@Composable
internal fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val access = remember { LocalNetworkAccess(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val activity = context as? Activity ?: return@rememberLauncherForActivityResult
        access.onPermissionResult(granted, activity)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, access) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (access.showRationale) {
        AlertDialog(
            onDismissRequest = access::dismissRationale,
            title = { Text(stringResource(R.string.local_network_title)) },
            text = { Text(stringResource(R.string.local_network_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (access.preferSettings) {
                            access.dismissRationale()
                            openAppSettings(context)
                        } else {
                            access.beginSystemRequest()
                            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        }
                    },
                ) {
                    Text(stringResource(R.string.local_network_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = access::dismissRationale) {
                    Text(stringResource(R.string.local_network_not_now))
                }
            },
        )
    }
    return access
}

@Composable
internal fun LocalNetworkDeniedMessage(access: LocalNetworkAccess) {
    if (!access.denied || access.granted) return
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.local_network_denied),
        color = MaterialTheme.colorScheme.error,
    )
    TextButton(
        onClick = {
            if (access.preferSettings) {
                openAppSettings(context)
            } else {
                access.request()
            }
        },
    ) {
        Text(
            stringResource(
                if (access.preferSettings) R.string.local_network_settings else R.string.local_network_try_again,
            ),
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}
