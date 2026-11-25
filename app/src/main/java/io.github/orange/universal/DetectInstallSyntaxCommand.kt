package io.github.orange.universal

import android.app.AlertDialog
import android.view.KeyEvent
import com.rk.commands.EditorFileActionContext
import com.rk.commands.EditorFileCommand
import com.rk.commands.EditorFileNonActionContext
import com.rk.commands.KeyCombination
import com.rk.icons.Icon
import java.util.Locale

/**
 * Detects the active editor file extension and downloads and installs its matching syntax pack from the online catalog.
 */
internal class DetectInstallSyntaxCommand(
    private val runtime: UlmRuntime
) : EditorFileCommand() {
    override val id: String = "io.github.orange.universal.detect-install-syntax"

    override fun getLabel(): String = "Detect & Download Syntax"

    override fun getIcon(): Icon = Icon.TextIcon("S")

    override val defaultKeybinds: KeyCombination =
        KeyCombination(keyCode = KeyEvent.KEYCODE_L, ctrl = true, shift = true)

    override fun isSupported(context: EditorFileNonActionContext): Boolean =
        context.file.isFile()

    override fun execute(context: EditorFileActionContext) {
        val activity = context.currentActivity
        val file = context.file
        val fileName = file.getName()
        val extension = file.getExtension().lowercase(Locale.ROOT)

        if (extension.isBlank()) {
            AlertDialog.Builder(activity)
                .setTitle("Syntax not detected")
                .setMessage("The active file has no extension: $fileName")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        Thread({
            val result = runCatching { runtime.installForExtension(extension) }
                .getOrElse { "Could not install syntax: ${it.message ?: it.javaClass.simpleName}\n" }

            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    AlertDialog.Builder(activity)
                        .setTitle("Syntax: .$extension")
                        .setMessage(result.trim())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }, "ulm-detect-syntax").apply {
            isDaemon = true
            start()
        }
    }
}
