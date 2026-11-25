package io.github.orange.universal

import androidx.annotation.Keep
import com.rk.commands.CommandProvider
import com.rk.commands.ToolbarConfiguration
import com.rk.extension.ExtensionAPI
import com.rk.extension.ExtensionContext
import com.rk.extension.api.DisposableManager

@Keep
@Suppress("unused")
class Main(context: ExtensionContext) : ExtensionAPI(context) {
    private val disposables = DisposableManager()
    private lateinit var runtime: UlmRuntime
    private lateinit var orangeCommand: OrangeCommand

    override fun onLoad() {
        runtime = UlmRuntime(context)
        runtime.start()

        val ulmPaletteCommand = UlmPaletteCommand()
        CommandProvider.registerCommand(ulmPaletteCommand, disposables)

        val detectSyntaxCommand = DetectInstallSyntaxCommand(runtime)
        CommandProvider.registerCommand(detectSyntaxCommand, disposables)

        // Add Orange to the editor action list. Xed places overflow actions
        // inside the three-dot menu when they do not fit in the toolbar.
        orangeCommand = OrangeCommand(
            childCommands = listOf(ulmPaletteCommand, detectSyntaxCommand)
        )
        CommandProvider.registerCommand(orangeCommand, disposables)
        ToolbarConfiguration.addEditorToolbarCommand(orangeCommand)
    }

    override fun onDispose() {
        if (::runtime.isInitialized) runtime.stop()
        if (::orangeCommand.isInitialized) {
            ToolbarConfiguration.removeEditorToolbarCommand(orangeCommand)
        }
        disposables.onDispose()
    }
}
