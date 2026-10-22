package com.conreo.couchytv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object Actions {

    fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun launchApp(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { toast(context, context.getString(R.string.toast_cannot_open)) }
        } else toast(context, context.getString(R.string.toast_no_launchable))
    }

    /**
     * Opens a Top Shelf title through its app's own deep link (a schema URI the
     * service publishes for launchers). If nothing on the box accepts it — or
     * the launch fails — fall back to opening [fallbackPkg] normally, so a
     * click never does nothing.
     *
     * Addressed to [fallbackPkg] explicitly: the URI was built from that app's
     * ids and, for iQIYI, carries that build's own action name. Two builds of
     * the same app can be installed side by side (银河奇异果 retail +
     * com.gitvvideo.oppo), and an unaddressed intent would open a chooser.
     */
    fun playShelf(context: Context, uri: String, fallbackPkg: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(fallbackPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The streaming app may be force-stopped/frozen; still wake it.
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        val resolves = runCatching {
            intent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
        if (resolves) {
            runCatching { context.startActivity(intent) }
                .onFailure {
                    toast(context, context.getString(R.string.toast_cannot_play))
                    launchApp(context, fallbackPkg)
                }
        } else {
            toast(context, context.getString(R.string.toast_cannot_play))
            launchApp(context, fallbackPkg)
        }
    }

    fun openAppInfo(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg")
                )
            )
        }
    }

    fun uninstall(context: Context, pkg: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
    }

    /** Best-effort: clears the app from cached memory. True force-stop is in App info. */
    fun close(context: Context, pkg: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        runCatching { am.killBackgroundProcesses(pkg) }
    }

    fun openSystemSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun openNetworkSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { openSystemSettings(context) }
    }

    fun openVpnSettings(context: Context) {
        runCatching { context.startActivity(Intent("android.settings.VPN_SETTINGS")) }
            .onFailure { openSystemSettings(context) }
    }

    /** AerialViews screensaver (github.com/theothernt/AerialViews) */
    const val AERIAL_PKG = "com.neilturner.aerialviews"

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun openAppStore(context: Context, pkg: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        }.onFailure {
            toast(context, context.getString(R.string.toast_no_store))
        }
    }

}
