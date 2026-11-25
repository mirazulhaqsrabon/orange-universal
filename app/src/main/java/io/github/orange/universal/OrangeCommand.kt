package io.github.orange.universal

import android.app.AlertDialog
import android.content.res.Resources
import com.rk.commands.EditorFileActionContext
import com.rk.commands.EditorFileCommand
import com.rk.commands.EditorFileNonActionContext
import com.rk.icons.Icon
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single Orange menu action.
 * Detects the active file extension and downloads/installs its syntax pack.
 */
internal class OrangeCommand(
    private val runtime: OrangeRuntime,
    private val resources: Resources
) : EditorFileCommand() {
    companion object {
        private val executionInProgress = AtomicBoolean(false)
    }
    override val id: String = "io.github.orange.universal.orange"

    override fun getLabel(): String = "Orange"

    override fun getIcon(): Icon = Icon.ExternalResourceIcon(R.drawable.orange_download, resources)

    override fun isSupported(context: EditorFileNonActionContext): Boolean =
        context.file.isFile()

    override fun execute(context: EditorFileActionContext) {
        val activity = context.currentActivity
        if (!executionInProgress.compareAndSet(false, true)) {
            AlertDialog.Builder(activity)
                .setTitle("Orange")
                .setMessage("Another Orange download is already running. Please wait for it to finish.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val fileName = context.file.getName()
        val extension = context.file.getExtension().lowercase(Locale.ROOT)

        if (extension.isBlank()) {
            executionInProgress.set(false)
            AlertDialog.Builder(activity)
                .setTitle("Orange")
                .setMessage("No file extension found for: $fileName\n\nOwner: Miraz")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        Thread({
            val result = runCatching { runtime.downloadForExtension(extension) }
                .getOrElse {
                    "Download failed: ${it.message ?: it.javaClass.simpleName}\n"
                }

            activity.runOnUiThread {
                try {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        AlertDialog.Builder(activity)
                            .setTitle("Orange · .$extension")
                            .setMessage("${result.trim()}\n\nOwner: Miraz")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } finally {
                    executionInProgress.set(false)
                }
            }
        }, "orange-download-syntax").apply {
            isDaemon = true
            start()
        }
    }
}
