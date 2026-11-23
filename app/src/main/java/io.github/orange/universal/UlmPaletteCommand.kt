package io.github.orange.universal

import android.app.AlertDialog
import com.rk.commands.ActionContext
import com.rk.commands.GlobalCommand
import com.rk.icons.Icon

internal class UlmPaletteCommand : GlobalCommand() {
    override val id: String = "io.github.orange.universal.open"
    override fun getLabel(): String = "Universal Language Manager"
    override fun getIcon(): Icon = Icon.TextIcon("ULM")

    override fun execute(context: ActionContext) {
        AlertDialog.Builder(context.currentActivity)
            .setTitle("Universal Language Manager")
            .setMessage(
                "Open the Xed Ubuntu terminal and run:\n\n" +
                    "ulm help\n\n" +
                    "Examples:\n" +
                    "ulm search python\n" +
                    "ulm install ms-python.python\n" +
                    "ulm list"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
