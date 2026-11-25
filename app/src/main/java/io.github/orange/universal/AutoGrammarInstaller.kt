package io.github.orange.universal

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rk.events.EditorEvent
import com.rk.events.EditorTabEvent
import com.rk.events.EventSubscription
import com.rk.events.Events
import com.rk.extension.ExtensionContext
import com.rk.file.BuiltinFileType
import com.rk.file.FileType
import com.rk.file.FileTypeManager
import com.rk.tabs.editor.EditorTab
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Downloads and registers a TextMate grammar when an opened file has no
 * registered Xed file type. Grammar paths are resolved from extensionFiles
 * by sora-editor's default FileResolver.
 */
internal class AutoGrammarInstaller(
    private val context: ExtensionContext,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val registeredTypes = ConcurrentHashMap<String, FileType>()

    private var openedSubscription: EventSubscription? = null
    private var selectedSubscription: EventSubscription? = null
    private var editorSubscription: EventSubscription? = null
    @Volatile private var cachedRegistry: List<LanguagePack>? = null

    private val grammarRoot: File
        get() = File(context.extensionFiles, "grammars")

    fun start() {
        if (!started.compareAndSet(false, true)) return

        openedSubscription = Events.subscribe<EditorTabEvent.Opened> { event ->
            scope.launch { installForTab(event.tab) }
        }
        selectedSubscription = Events.subscribe<EditorTabEvent.Selected> { event ->
            scope.launch { installForTab(event.tab) }
        }
        editorSubscription = Events.subscribe<EditorEvent.InstanceCreated> { event ->
            val tab = event.editor.ownerTab as? EditorTab ?: return@subscribe
            scope.launch {
                val scopeName = tab.editorState.textmateScope ?: return@launch
                if (registeredTypes.values.any { it.textmateScope == scopeName }) {
                    runCatching { event.editor.configureLanguage(scopeName) }
                        .onFailure { Log.w(TAG, "Could not apply downloaded grammar", it) }
                }
            }
        }

        Log.i(TAG, "Auto Grammar Installer started")
    }

    private suspend fun installForTab(tab: EditorTab) {
        val file = tab.file ?: return
        val fileName = file.getName()
        val extension = file.getExtension().lowercase().removePrefix(".")
        if (extension.isBlank()) return

        runCatching {
            installMutex.withLock {
                val existingType = FileTypeManager.fromFileName(fileName)
                val existingScope = existingType.textmateScope

                // Xed's built-in language types should be left untouched.
                if (existingType != BuiltinFileType.UNKNOWN) {
                    if (existingScope != null && GrammarRegistry.getInstance().findGrammar(existingScope) != null) {
                        return
                    }
                    // Built-in file types have priority over extension types.
                    return
                }

                val pack = registry().firstOrNull { item ->
                    item.extensions.any { it.equals(extension, ignoreCase = true) }
                } ?: return

                val grammarScope = installPack(pack)
                val fileType = DynamicFileType(
                    extensions = pack.extensions,
                    textmateScope = grammarScope,
                    name = pack.id,
                    title = pack.title,
                )

                FileTypeManager.register(fileType)
                registeredTypes[pack.id] = fileType

                tab.editorState.textmateScope = grammarScope
                tab.editorState.contentLoaded.await()
                tab.editorState.editor.get()?.let { editor ->
                    editor.configureLanguage(grammarScope)
                }

                Log.i(TAG, "Installed ${pack.title} grammar for $fileName")
            }
        }.onFailure {
            Log.w(TAG, "Auto grammar installation failed for $fileName", it)
        }
    }

    private suspend fun registry(): List<LanguagePack> {
        cachedRegistry?.let { return it }

        val indexBytes = downloadBytes(REGISTRY_URL, MAX_INDEX_BYTES)
        val root = JsonParser.parseString(
            String(indexBytes, StandardCharsets.UTF_8)
        ).asJsonObject
        val languages = root.getAsJsonArray("languages") ?: JsonArray()
        val parsed = languages.mapNotNull { element ->
            runCatching {
                val json = element.asJsonObject
                val id = json.get("id").asString
                val title = json.get("title").asString
                val scopeName = json.get("scopeName").asString
                val grammarUrl = json.get("grammarUrl").asString
                val extensions = json.getAsJsonArray("extensions").map {
                    it.asString.lowercase().removePrefix(".")
                }
                val configUrl = json.get("configUrl")?.takeUnless { it.isJsonNull }?.asString

                require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
                require(title.isNotBlank() && scopeName.isNotBlank())
                require(extensions.isNotEmpty() && extensions.all {
                    it.matches(Regex("[a-z0-9][a-z0-9._+-]{0,31}"))
                })
                require(isTrustedUrl(grammarUrl))
                require(configUrl == null || isTrustedUrl(configUrl))

                LanguagePack(id, title, scopeName, extensions, grammarUrl, configUrl)
            }.getOrNull()
        }

        cachedRegistry = parsed
        return parsed
    }

    private suspend fun installPack(pack: LanguagePack): String {
        val folder = File(grammarRoot, pack.id)
        if (!folder.exists() && !folder.mkdirs()) {
            error("Unable to create grammar directory")
        }

        val grammarFile = File(folder, "grammar.tmLanguage.json")
        if (!grammarFile.isFile || grammarFile.length() == 0L) {
            downloadToFile(pack.grammarUrl, grammarFile, MAX_GRAMMAR_BYTES)
        }

        val grammarJson = JsonParser.parseString(grammarFile.readText()).asJsonObject
        val actualScope = grammarJson.get("scopeName")?.asString
            ?: error("Grammar is missing scopeName")
        require(actualScope == pack.scopeName) {
            "Grammar scope mismatch: expected ${pack.scopeName}, received $actualScope"
        }

        val languageDefinition = JsonObject()
        val languages = JsonArray()
        val language = JsonObject().apply {
            addProperty("grammar", grammarFile.absolutePath)
            addProperty("name", pack.id)
            addProperty("scopeName", pack.scopeName)
        }

        pack.configUrl?.let { url ->
            val configFile = File(folder, "language-configuration.json")
            if (!configFile.isFile || configFile.length() == 0L) {
                downloadToFile(url, configFile, MAX_CONFIG_BYTES)
            }
            JsonParser.parseString(configFile.readText()).asJsonObject
            language.addProperty("languageConfiguration", configFile.absolutePath)
        }

        languages.add(language)
        languageDefinition.add("languages", languages)

        val definitionFile = File(folder, "languages.json")
        definitionFile.writeText(languageDefinition.toString())

        val grammarRegistry = GrammarRegistry.getInstance()
        if (grammarRegistry.findGrammar(pack.scopeName) == null) {
            grammarRegistry.loadGrammars(definitionFile.absolutePath)
        }
        check(grammarRegistry.findGrammar(pack.scopeName) != null) {
            "Xed did not register grammar ${pack.scopeName}"
        }

        return pack.scopeName
    }

    private suspend fun downloadToFile(url: String, target: File, maxBytes: Int) {
        val bytes = downloadBytes(url, maxBytes)
        val temporary = File(target.parentFile, target.name + ".part")
        temporary.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Unable to replace cached grammar file")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Unable to finalize downloaded grammar file")
        }
    }

    private suspend fun downloadBytes(url: String, maxBytes: Int): ByteArray {
        require(isTrustedUrl(url)) { "Only trusted HTTPS grammar sources are allowed" }

        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Orange-Universal-Grammar-Installer")
        }

        try {
            connection.connect()
            require(connection.url.host.equals(TRUSTED_HOST, ignoreCase = true)) {
                "Grammar source redirected to an untrusted host"
            }
            require(connection.responseCode == HttpsURLConnection.HTTP_OK) {
                "Grammar download returned HTTP ${connection.responseCode}"
            }
            return connection.inputStream.use { readLimited(it, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Downloaded file exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isTrustedUrl(value: String): Boolean {
        return runCatching {
            val url = URL(value)
            url.protocol == "https" && url.host.equals(TRUSTED_HOST, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun dispose() {
        openedSubscription?.unsubscribe()
        selectedSubscription?.unsubscribe()
        editorSubscription?.unsubscribe()
        registeredTypes.values.forEach { FileTypeManager.unregister(it) }
        registeredTypes.clear()
        scope.cancel()
    }

    private data class LanguagePack(
        val id: String,
        val title: String,
        val scopeName: String,
        val extensions: List<String>,
        val grammarUrl: String,
        val configUrl: String?,
    )

    private data class DynamicFileType(
        override val extensions: List<String>,
        override val textmateScope: String,
        override val name: String,
        override val title: String,
    ) : FileType {
        override val icon: com.rk.icons.Icon? = null
    }

    private companion object {
        const val TAG = "OrangeGrammar"
        const val TRUSTED_HOST = "raw.githubusercontent.com"
        const val REGISTRY_URL =
            "https://raw.githubusercontent.com/mirazulhaqsrabon/orange-universal/main/registry/index.json"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_INDEX_BYTES = 512 * 1024
        const val MAX_GRAMMAR_BYTES = 3 * 1024 * 1024
        const val MAX_CONFIG_BYTES = 512 * 1024
    }
}
