package com.conreo.couchytv

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.conreo.couchytv.ui.LauncherApp
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Bumped whenever a package is installed/removed so the app grid rescans. */
    var rescanTick by mutableIntStateOf(0)
        private set

    /**
     * Which half of the launcher the system asked for.
     *
     * This firmware never delivers the remote's "app" key as a KeyEvent — it folds
     * KEYCODE_F3 into the HOME branch (see docs/oppo-tv-key-handling.md). What it
     * does preserve is WHICH key was pressed: the HOME intent it fires carries a
     * different category per key. The stock launcher switches between its home
     * page and its app list exactly this way.
     *
     *   home key  → cat=[android.intent.category.HOME]
     *   app  key  → cat=[com.heytap.tv.launcher.category.MODERN_HOME]
     *
     * The intent is an explicit component, so it always reaches us; only the
     * category differs. Counters (not booleans) so a repeat press re-applies the
     * state even if the user moved the dock by hand in between.
     */
    var appListTick by mutableIntStateOf(0)
        private set
    var homeTick by mutableIntStateOf(0)
        private set

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            // Removals always rescan (the app may be on the grid). For installs and
            // changes, skip the full scan unless the package is actually launchable.
            val launchable = pkg != null && (
                packageManager.getLeanbackLaunchIntentForPackage(pkg) != null ||
                    packageManager.getLaunchIntentForPackage(pkg) != null
                )
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED || launchable) {
                rescanTick++
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Read the chosen UI language synchronously from SharedPreferences — cheap,
        // and off the DataStore (a runBlocking DataStore read here blocked the main
        // thread and made language changes hang until a restart). LauncherApp keeps
        // this mirror in sync with the DataStore config and triggers recreate().
        val lang = newBase.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
            .getString(LOCALE_KEY, "").orEmpty()
        super.attachBaseContext(if (lang.isEmpty()) newBase else applyLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read the launch intent HERE, not in the property initialiser above: the
        // field initialiser runs before onCreate, when getIntent() can still be the
        // intent the Activity was constructed with rather than the one that started
        // it — the app-key cold start was being read as a plain HOME launch.
        if (isAppListIntent(intent)) appListTick = 1

        // A home screen never exits on Back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op */ }
        })

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            LauncherApp(rescanTick, appListTick, homeTick)
        }
    }

    /**
     * Hot start: the launcher is already up and the system re-delivers the HOME
     * intent for the key that was pressed. `singleTask` routes it here instead of
     * through `onCreate`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in sync so a later re-read (and hasCategory below)
        // sees the intent that actually brought us forward.
        setIntent(intent)
        if (isAppListIntent(intent)) {
            appListTick++
        } else if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeTick++
        }
    }

    private fun isAppListIntent(intent: Intent?): Boolean =
        intent?.hasCategory(MODERN_HOME_CATEGORY) == true

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    /**
     * Everything heavy the launcher holds — tile artwork, Top Shelf stills — is
     * also on disk. When the system asks for memory back, actually give some:
     * free the caches and let a re-composed card re-decode its WebP instead of
     * being killed in the background.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            com.conreo.couchytv.data.AppArtworkCache.clear()
            com.conreo.couchytv.data.Shelf.trimMemory()
        }
    }

    companion object {
        /**
         * OPPO/ColorOS TV private category. The firmware stamps it on the HOME
         * intent when the remote's "app" key is pressed. Declared in the manifest
         * as well — boot waits for a home activity carrying it, so it must stay.
         */
        const val MODERN_HOME_CATEGORY = "com.heytap.tv.launcher.category.MODERN_HOME"

        private const val LOCALE_PREFS = "locale"
        private const val LOCALE_KEY = "lang"

        /** Mirror the chosen language so attachBaseContext can read it synchronously. */
        fun persistLocale(context: Context, lang: String) {
            context.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .edit().putString(LOCALE_KEY, lang).apply()
        }

        fun currentLocalePref(context: Context): String =
            context.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .getString(LOCALE_KEY, "").orEmpty()

        fun applyLocale(context: Context, lang: String): Context {
            val locale = if (lang.contains('-')) {
                val parts = lang.split('-')
                Locale(parts[0], parts.getOrElse(1) { "" })
            } else Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }
}
