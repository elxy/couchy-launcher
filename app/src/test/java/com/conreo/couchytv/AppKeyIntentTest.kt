package com.conreo.couchytv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The remote's "app" key is not a KeyEvent on this firmware: PhoneWindowManager
 * folds KEYCODE_F3 into the HOME branch and marks the HOME intent with a private
 * category instead (docs/oppo-tv-key-handling.md). These tests pin the wiring down
 * so a refactor cannot silently break the app-list key again.
 *
 * Kept as source assertions because the project has no Android test runtime
 * (no Robolectric) and this is pure plumbing, not rendering.
 */
class AppKeyIntentTest {

    private val activity = File("src/main/java/com/conreo/couchytv/MainActivity.kt").readText()
    private val launcher = File("src/main/java/com/conreo/couchytv/ui/LauncherScreen.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `manifest keeps the private category, so boot does not hang`() {
        // ColorOS TV waits for a home activity carrying this category at boot and
        // hangs on the boot animation without it. Removing it to "clean up" the
        // intent handling would break boot.
        assertTrue(
            "AndroidManifest must declare MODERN_HOME",
            manifest.contains(MainActivity.MODERN_HOME_CATEGORY),
        )
    }

    @Test
    fun `hot start re-reads the NEW intent, not the stale one`() {
        // A `singleTask` home activity is reused: onNewIntent is the only hook that
        // sees the key that was actually pressed. Reading getIntent() there instead
        // of the parameter would keep serving the launch intent forever.
        assertTrue(activity.contains("override fun onNewIntent(intent: Intent)"))
        assertTrue("must setIntent(intent) to keep getIntent() in sync",
            activity.contains("setIntent(intent)"))
        assertTrue("must handle the MODERN_HOME category",
            activity.contains("MODERN_HOME_CATEGORY"))
    }

    @Test
    fun `cold start reads the launch intent inside onCreate, not a field initialiser`() {
        // Regression: the tick was seeded by a property initialiser,
        // `mutableIntStateOf(if (isAppListIntent(intent)) 1 else 0)`. That runs
        // before onCreate, when getIntent() can still be the constructor's intent
        // rather than the one that started the Activity — an app-key cold start was
        // silently read as a plain HOME launch and the grid never opened.
        val onCreate = activity.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onNewIntent")
        assertTrue("must seed the tick from the launch intent inside onCreate",
            onCreate.contains("if (isAppListIntent(intent)) appListTick = 1"))
        assertTrue("the property initialiser must stay at 0",
            activity.contains("var appListTick by mutableIntStateOf(0)"))
        // `seen` starts at 0, the value a plain HOME launch carries, so an ordinary
        // boot does not pop the grid open.
        assertTrue(launcher.contains("var seenAppListTick by remember { mutableIntStateOf(0) }"))
    }

    @Test
    fun `opening the grid is suppressed while a dialog or move is live`() {
        // Dropping the dock state under a dialog or mid-move would strand the user.
        val guard = launcher.substringAfter("LaunchedEffect(appListRequested,")
            .substringBefore("var seenHomeTick")
        assertTrue("must ignore the key while a dialog/move is active",
            guard.contains("showSettings || movePkg != null || menuFor != null"))
    }

    @Test
    fun `a cold start applies the request once the apps are available`() {
        // Regression: the first version opened the grid directly inside
        // LaunchedEffect(appListTick). On a cold start that effect runs while the
        // app scan is still empty, the `dockList.items.isNotEmpty()` guard made it
        // a silent no-op, and the app key was lost — the shelf came up on the home
        // page instead. The press is now latched and replayed when the list fills.
        assertTrue("the press must be latched",
            launcher.contains("var appListRequested by remember { mutableStateOf(false) }"))
        assertTrue("latching must not depend on the list already being there",
            launcher.contains("appListRequested = true"))
        assertTrue("the replay must react to the list growing",
            launcher.contains("LaunchedEffect(appListRequested, dockList.items.size, config.layout)"))
    }

    @Test
    fun `home cancels a pending app list request`() {
        // Pressing home while the grid is still loading must win, otherwise the
        // late-replayed request would drag the user into the app list afterwards.
        val block = launcher.substringAfter("LaunchedEffect(homeTick)")
            .substringBefore("----- move-mode helpers -----")
        assertTrue("home must clear the pending request", block.contains("appListRequested = false"))
    }
}
