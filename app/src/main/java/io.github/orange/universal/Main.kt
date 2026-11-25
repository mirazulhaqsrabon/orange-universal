package io.github.orange.universal

import androidx.annotation.Keep
import com.rk.extension.ExtensionAPI
import com.rk.extension.ExtensionContext

@Keep
@Suppress("unused")
class Main(context: ExtensionContext) : ExtensionAPI(context) {
    private val grammarInstaller = AutoGrammarInstaller(context)

    override fun onLoad() {
        grammarInstaller.start()
    }

    override fun onDispose() {
        grammarInstaller.dispose()
    }
}
