package io.github.orange.universal

import androidx.annotation.Keep
import com.rk.commands.CommandProvider
import com.rk.extension.ExtensionAPI
import com.rk.extension.ExtensionContext
import com.rk.extension.api.DisposableManager

@Keep
@Suppress("unused")
class Main(context: ExtensionContext) : ExtensionAPI(context) {
    private val disposables = DisposableManager()
    private lateinit var runtime: UlmRuntime

    override fun onLoad() {
        runtime = UlmRuntime(context)
        runtime.start()
        CommandProvider.registerCommand(UlmPaletteCommand(), disposables)
    }

    override fun onDispose() {
        if (::runtime.isInitialized) runtime.stop()
        disposables.onDispose()
    }
}
