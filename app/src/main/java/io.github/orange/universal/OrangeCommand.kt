package io.github.orange.universal

import android.app.AlertDialog
import android.content.res.Resources
import com.rk.commands.EditorFileActionContext
import com.rk.commands.EditorFileCommand
import com.rk.commands.EditorFileNonActionContext
import com.rk.icons.Icon
import java.util.Locale

/**
 * The single Orange menu action.
 * Detects the active file extension and downloads/installs its syntax pack.
 */
internal class OrangeCommand(
    private val runtime: UlmRuntime,
    private val resources: Resources
) : EditorFileCommand() {
    override val id: String = "io.github.orange.universal.orange"

    override fun getLabel(): String = "Orange"

    override fun getIcon(): Icon = Icon.ExternalResourceIcon(R.drawable.orange_download, resources)

    override fun isSupported(context: EditorFileNonActionContext): Boolean =
        context.file.isFile()

    override fun execute(context: EditorFileActionContext) {
        val activity = context.currentActivity
        val fileName = context.file.getName()
        val extension = context.file.getExtension().lowercase(Locale.ROOT)

        if (extension.isBlank()) {
            AlertDialog.Builder(activity)
                .setTitle("Orange")
                .setMessage("No file extension found for: $fileName")
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
                if (!activity.isFinishing && !activity.isDestroyed) {
                    AlertDialog.Builder(activity)
                        .setTitle("Orange · .$extension")
                        .setMessage(result.trim())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }, "orange-download-syntax").apply {
            isDaemon = true
            start()
        }
    }
}
