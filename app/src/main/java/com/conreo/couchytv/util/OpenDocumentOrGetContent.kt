package com.conreo.couchytv.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Opens a document picker, falling back to ACTION_GET_CONTENT when the device has no real
 * document provider.
 *
 * Some Android TV sets ship `com.google.android.tv.frameworkpackagestubs`, whose
 * `Stubs$DocumentsStub` declares ACTION_OPEN_DOCUMENT and then does nothing. The picker
 * resolves, launches, and closes immediately — the user sees "no application can handle this
 * action" or nothing at all. ACTION_GET_CONTENT is answered by the vendor's gallery/file
 * manager on those same sets, so we use it whenever OPEN_DOCUMENT has no usable handler.
 *
 * Caveat when the fallback is taken: GET_CONTENT grants no persistable URI permission, so a
 * caller that keeps the URI across restarts (the video wallpaper) gets a dead URI after a
 * reboot. Callers that copy the content immediately (the photo wallpaper, config load) are
 * unaffected.
 */
class OpenDocumentOrGetContent : ActivityResultContract<Array<String>, Uri?>() {

    override fun createIntent(context: Context, input: Array<String>): Intent {
        val mimeTypes = input.ifEmpty { arrayOf("*/*") }

        val openDocument = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        if (hasUsableHandler(context, openDocument)) return openDocument

        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Several packages can claim GET_CONTENT (typically the vendor gallery plus the same
        // placeholder stubs). If exactly one real app answers, aim straight at it; the system
        // chooser would otherwise offer the placeholders too.
        val candidates = realHandlers(context, getContent)
        if (candidates.size == 1) getContent.setPackage(candidates.first())
        return getContent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null

    /** Package names of activities that answer [intent] and are not known placeholders. */
    private fun realHandlers(context: Context, intent: Intent): List<String> =
        context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filterNot { it in PLACEHOLDER_PACKAGES }

    private fun hasUsableHandler(context: Context, intent: Intent): Boolean =
        realHandlers(context, intent).isNotEmpty()

    private companion object {
        /** Packages whose activities claim file intents but render nothing. */
        val PLACEHOLDER_PACKAGES = setOf("com.google.android.tv.frameworkpackagestubs")
    }
}
