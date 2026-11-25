package io.github.orange.universal

import com.rk.commands.Command
import com.rk.commands.GlobalCommand
import com.rk.icons.Icon

/**
 * Entry point for Orange in the editor's overflow menu.
 * Selecting it opens a command palette containing the ULM actions.
 */
internal class OrangeCommand(
    override val childCommands: List<Command>
) : GlobalCommand() {
    override val id: String = "io.github.orange.universal.orange"

    override fun getLabel(): String = "Orange"

    override fun getIcon(): Icon = Icon.TextIcon("O")

    override fun execute(context: com.rk.commands.ActionContext) {
        // GlobalCommand.perform() opens childCommands; this is a safe fallback.
    }
}
