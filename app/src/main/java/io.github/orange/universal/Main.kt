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

        // Orange is the only registered editor/menu command.
        orangeCommand = OrangeCommand(runtime, context.resources)
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
