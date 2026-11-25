package io.github.orange.universal

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.rk.file.FileType
import com.rk.file.FileTypeManager
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.FileResolver
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class InstalledLanguage(
    val id: String,
    val title: String,
    val scope: String,
    val extensions: List<String>,
    val grammar: String,
    val configuration: String?,
    val version: String,
    val enabled: Boolean = true
)

internal class OrangeRuntime(
    private val extensionContext: com.rk.extension.ExtensionContext
) {
    private val gson = Gson()
    private val privateRoot = extensionContext.appContext.filesDir.parentFile
        ?: throw IllegalStateException("Xed private directory is unavailable")
    private val home = File(privateRoot, "local/home")
    private val data = File(home, ".local/share/orange")
    private val requestDir = File(data, "bridge/requests")
    private val responseDir = File(data, "bridge/responses")
    private val grammarDir = File(data, "grammars")
    private val metadataFile = File(data, "installed.json")
    private val cliFile = File(privateRoot, "local/bin/orange")
    private val registeredTypes = mutableMapOf<String, FileType>()
    private val installLocks = ConcurrentHashMap<String, Any>()
    @Volatile private var running = false
    private var worker: Thread? = null

    private val resolver = FileResolver { requestedPath ->
        if (!requestedPath.startsWith("orange/")) null else runCatching {
            val root = data.canonicalFile
            val file = File(root, requestedPath.removePrefix("orange/")).canonicalFile
            if (file.path.startsWith(root.path + File.separator) && file.isFile) file.inputStream() else null
        }.getOrNull()
    }

    private fun migrateLegacyData() {
        val legacyData = File(home, ".local/share/ulm")
        if (!legacyData.exists() || data.exists()) return

        if (!legacyData.renameTo(data)) {
            extensionContext.logWarn("Could not migrate the previous language-pack data directory.")
            return
        }

        if (metadataFile.isFile) {
            val migrated = metadataFile.readText()
                .replace("\"ulm/", "\"orange/")
            metadataFile.writeText(migrated)
        }

        File(privateRoot, "local/bin/ulm").delete()
    }

    fun start() {
        migrateLegacyData()
        data.mkdirs()
        requestDir.mkdirs()
        responseDir.mkdirs()
        grammarDir.mkdirs()
        File(privateRoot, "local/bin").mkdirs()
        installCli()
        FileProviderRegistry.getInstance().addFileProvider(resolver)
        restoreInstalledLanguages()
        running = true
        worker = Thread({
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    requestDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
                        ?.sortedBy { it.name }?.forEach { request ->
                            if (running) processRequest(request)
                        }
                    Thread.sleep(120)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    extensionContext.logError("Orange bridge error: ${e.message}")
                }
            }
        }, "orange-command-bridge").apply {
            isDaemon = true
            start()
        }
        extensionContext.logInfo("Orange is ready. Syntax packs are downloaded from the online GitHub catalog.")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        FileProviderRegistry.getInstance().removeFileProvider(resolver)
        registeredTypes.values.forEach { runCatching { FileTypeManager.unregister(it) } }
        registeredTypes.clear()
    }

    private fun installCli() {
        val script = """#!/bin/sh
set -eu
BASE="@@DOLLAR@@{HOME:-/home}/.local/share/orange/bridge"
REQ="@@DOLLAR@@BASE/requests"
RES="@@DOLLAR@@BASE/responses"
mkdir -p "@@DOLLAR@@REQ" "@@DOLLAR@@RES"
ID="@@DOLLAR@@(date +%s%N)-@@DOLLAR@@@@DOLLAR@@"
OUT="@@DOLLAR@@RES/@@DOLLAR@@ID"
TMP="@@DOLLAR@@REQ/.@@DOLLAR@@ID.tmp"
printf '%s\n' "@@DOLLAR@@@" > "@@DOLLAR@@TMP"
mv "@@DOLLAR@@TMP" "@@DOLLAR@@REQ/@@DOLLAR@@ID"
COUNT=0
while [ ! -f "@@DOLLAR@@OUT" ]; do
  COUNT=@@DOLLAR@@((COUNT + 1))
  if [ "@@DOLLAR@@COUNT" -ge 3600 ]; then
    rm -f "@@DOLLAR@@REQ/@@DOLLAR@@ID"
    echo "orange: extension did not respond. Make sure Orange is enabled in Xed."
    exit 1
  fi
  sleep 0.1
done
cat "@@DOLLAR@@OUT"
rm -f "@@DOLLAR@@OUT"
""".replace("@@DOLLAR@@", "$")
        cliFile.parentFile?.mkdirs()
        cliFile.writeText(script)
        cliFile.setExecutable(true, false)
    }

    private fun processRequest(request: File) {
        val published = File(responseDir, request.name)
        val response = File(responseDir, ".${request.name}.${Thread.currentThread().id}.tmp")
        try {
            val args = request.readLines().filter { it.isNotEmpty() }
            response.writeText(handle(args))
            if (!response.renameTo(published)) throw IllegalStateException("Could not publish command response")
        } catch (e: Exception) {
            response.delete()
            val failure = File(responseDir, ".${request.name}.error.tmp")
            failure.writeText("orange: error: ${e.message ?: e.javaClass.simpleName}\n")
            if (!failure.renameTo(published)) extensionContext.logError("Could not publish error response for ${request.name}")
        } finally {
            request.delete()
        }
    }

    private fun handle(args: List<String>): String {
        if (args.isEmpty()) return help()
        return when (args[0].lowercase(Locale.ROOT)) {
            "help", "--help", "-h" -> help()
            "search" -> search(args.drop(1).joinToString(" "))
            "install", "add" -> if (args.size != 2) "Usage: orange install <language-id>\n" else install(args[1])
            "remove", "uninstall" -> if (args.size != 2) "Usage: orange remove <language-id>\n" else remove(args[1])
            "list", "ls" -> listLanguages()
            "info" -> if (args.size != 2) "Usage: orange info <language-id>\n" else info(args[1])
            "update" -> update(args.getOrNull(1))
            "enable" -> if (args.size != 2) "Usage: orange enable <language-id>\n" else setEnabled(args[1], true)
            "disable" -> if (args.size != 2) "Usage: orange disable <language-id>\n" else setEnabled(args[1], false)
            "status" -> status()
            "test-online", "online" -> testOnlineDownload()
            "version", "--version", "-v" -> "Orange 1.0.0\n"
            else -> "Unknown command: ${args[0]}\nRun 'orange help' for usage.\n"
        }
    }

    private fun help() = """Orange 1.0.0

Usage:
  orange help                    Show command help
  orange search <query>          Search online language catalog
  orange install <language-id>   Download and install a language
  orange remove <language-id>    Remove an installed language
  orange list                    List installed languages
  orange info <language-id>      Show language details
  orange update [language-id]    Download and reinstall language assets
  orange enable <language-id>    Enable a language file type
  orange disable <language-id>   Disable a language file type
  orange status                  Show manager status
  orange test-online             Test GitHub catalog and asset download
  orange version                 Show version

Example:
  orange search astro
  orange install astro
  orange list
  orange remove astro

Language packs are downloaded from the Orange GitHub repository when available.
"""

    private val remoteCatalogUrl =
        "https://raw.githubusercontent.com/mirazulhaqsrabon/orange-universal/bf113ea8ff97897b3fa31d159f847d39a1981cf6/language-packs/languages.json"

    private fun catalog(): List<JsonObject> = try {
        val root = JsonParser.parseString(downloadText(remoteCatalogUrl)).asJsonObject
        root.getAsJsonArray("languages")?.map { it.asJsonObject } ?: emptyList()
    } catch (e: Exception) {
        extensionContext.logWarn("Could not load online language catalog: " + e.message)
        emptyList()
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Orange/1.1")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code while downloading catalog")
            val limit = 2L * 1024L * 1024L
            if (connection.contentLengthLong > limit) throw IllegalStateException("Remote catalog exceeds 2 MB")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw IllegalStateException("Remote catalog exceeds 2 MB")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadRemoteAsset(languageId: String, assetName: String, destination: File) {
        if (!languageId.matches(Regex("[a-z0-9][a-z0-9._+-]*")) || safeAssetName(assetName) == null) {
            throw IllegalArgumentException("Invalid remote asset path")
        }
        val url = "https://raw.githubusercontent.com/mirazulhaqsrabon/orange-universal/bf113ea8ff97897b3fa31d159f847d39a1981cf6/language-packs/" + languageId + "/" + assetName
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 30000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Orange/1.1")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code downloading $assetName")
            if (connection.contentLengthLong > 20L * 1024L * 1024L) throw IllegalStateException("Remote asset exceeds 20 MB")
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 20L * 1024L * 1024L) throw IllegalStateException("Remote asset exceeds 20 MB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!destination.isFile || destination.length() == 0L) throw IllegalStateException("Downloaded asset is empty")
        } finally {
            connection.disconnect()
        }
    }

    private fun testOnlineDownload(): String {
        val root = JsonParser.parseString(downloadText(remoteCatalogUrl)).asJsonObject
        val languages = root.getAsJsonArray("languages") ?: throw IllegalStateException("Online catalog has no languages")
        val astro = languages.map { it.asJsonObject }.firstOrNull { it.stringOrNull("id") == "astro" }
            ?: throw IllegalStateException("Astro pack is missing from online catalog")
        val grammarName = safeAssetName(astro.string("grammar")) ?: throw IllegalStateException("Invalid grammar filename")
        val configName = astro.stringOrNull("languageConfiguration")?.let {
            safeAssetName(it) ?: throw IllegalStateException("Invalid language configuration filename")
        }
        val grammarFile = File(grammarDir, ".online-grammar-test.tmp")
        val configFile = configName?.let { File(grammarDir, ".online-config-test.tmp") }
        try {
            downloadRemoteAsset("astro", grammarName, grammarFile)
            val grammar = JsonParser.parseString(grammarFile.readText()).asJsonObject
            if (grammar.stringOrNull("scopeName") != astro.stringOrNull("scopeName")) {
                throw IllegalStateException("Downloaded grammar scope does not match catalog")
            }
            if (configName != null && configFile != null) {
                downloadRemoteAsset("astro", configName, configFile)
                JsonParser.parseString(configFile.readText())
            }
            return buildString {
                append("Online download test successful.\n")
                append("GitHub catalog: OK\n")
                append("Astro grammar: OK (" + grammarFile.length() + " bytes)\n")
                if (configFile != null) append("Astro configuration: OK (" + configFile.length() + " bytes)\n")
                append("Source: GitHub raw content\n")
            }
        } finally {
            grammarFile.delete()
            configFile?.delete()
        }
    }

    private fun search(query: String): String {
        if (query.isBlank()) return "Usage: orange search <query>\n"
        val matches = catalog().filter { item ->
            item.stringOrNull("id").orEmpty().contains(query, true) ||
                item.stringOrNull("name").orEmpty().contains(query, true) ||
                item.stringOrNull("description").orEmpty().contains(query, true)
        }
        if (matches.isEmpty()) return "No online language pack found for '$query'. Check your internet connection or the GitHub catalog.\n"
        return buildString {
            append("Online language packs:\n\n")
            matches.forEach { item ->
                val id = item.string("id")
                val extensionList = item.getAsJsonArray("extensions").joinToString { ".${it.asString.removePrefix(".")}" }
                append("• $id — ${item.string("name")}\n")
                append("  Extensions: $extensionList\n")
                append("  Install: orange install $id\n")
            }
        }
    }

    fun downloadForExtension(extensionInput: String): String {
        val extension = extensionInput.removePrefix(".").lowercase(Locale.ROOT)
        if (extension.isBlank()) return "The active file has no extension.\n"

        val metadata = catalog().firstOrNull { item ->
            item.getAsJsonArray("extensions")?.any { it.asString.removePrefix(".").equals(extension, true) } == true
        } ?: return "No syntax pack is available for '.$extension' yet.\n"

        val id = metadata.string("id")
        // Always fetch a fresh copy when the user taps Orange.
        return install(id)
    }

    private fun install(idInput: String): String {
        val id = idInput.lowercase(Locale.ROOT)
        val lock = installLocks.computeIfAbsent(id) { Any() }
        return synchronized(lock) { installInternal(id) }
    }

    private fun installInternal(id: String): String {
        if (!id.matches(Regex("[a-z0-9][a-z0-9._+-]*"))) return "Invalid language id.\n"
        val metadata = catalog().firstOrNull { it.stringOrNull("id").equals(id, true) }
            ?: return "Language '$id' is not available in the online GitHub catalog. Try: orange search astro\n"

        val scope = metadata.string("scopeName")
        if (!scope.matches(Regex("[A-Za-z0-9_.+-]+"))) throw IllegalArgumentException("Invalid TextMate scope")
        val extensions = metadata.getAsJsonArray("extensions")?.mapNotNull { element ->
            element.asString.removePrefix(".").takeIf { it.matches(Regex("[A-Za-z0-9_+-]+")) }
        }.orEmpty()
        if (extensions.isEmpty()) throw IllegalArgumentException("Language has no valid file extensions")

        val grammarName = safeAssetName(metadata.string("grammar"))
            ?: throw IllegalArgumentException("Grammar must be a root-level asset filename")
        val configName = metadata.stringOrNull("languageConfiguration")?.let {
            safeAssetName(it) ?: throw IllegalArgumentException("Language configuration must be a root-level asset filename")
        }
        val staging = File(grammarDir, ".$id-staging-${Thread.currentThread().id}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IllegalStateException("Could not create language staging directory")

        try {
            val grammarFile = File(staging, grammarName)
            downloadRemoteAsset(id, grammarName, grammarFile)
            val grammarJson = JsonParser.parseString(File(staging, grammarName).readText()).asJsonObject
            if (grammarJson.stringOrNull("scopeName") != scope) {
                throw IllegalArgumentException("Grammar scope does not match languages.json")
            }

            var configurationPath: String? = null
            if (configName != null) {
                val configFile = File(staging, configName)
                downloadRemoteAsset(id, configName, configFile)
                JsonParser.parseString(File(staging, configName).readText())
                configurationPath = "orange/grammars/$id/$configName"
            }

            val destination = File(grammarDir, id)
            val backup = File(grammarDir, ".$id-backup")
            backup.deleteRecursively()
            val previousEntries = readInstalled()
            var movedPrevious = false
            var installedNew = false
            try {
                if (destination.exists()) {
                    if (!destination.renameTo(backup)) throw IllegalStateException("Could not preserve the previous language pack")
                    movedPrevious = true
                }
                if (!staging.renameTo(destination)) throw IllegalStateException("Could not install language assets")
                installedNew = true

                val entry = InstalledLanguage(
                    id = id,
                    title = metadata.string("name"),
                    scope = scope,
                    extensions = extensions,
                    grammar = "orange/grammars/$id/$grammarName",
                    configuration = configurationPath,
                    version = metadata.stringOrNull("version") ?: "1.0.0",
                    enabled = true
                )
                val entries = previousEntries.toMutableList()
                entries.removeAll { it.id == id }
                entries.add(entry)
                writeInstalled(entries)
                registerLanguage(entry)
                backup.deleteRecursively()
                return "Installed ${entry.title} ${entry.version}\n  Scope: ${entry.scope}\n  Files: ${entry.extensions.joinToString { ".$it" }}\n"
            } catch (e: Exception) {
                if (installedNew && destination.exists()) destination.deleteRecursively()
                if (movedPrevious && backup.exists() && !backup.renameTo(destination)) {
                    extensionContext.logError("Could not restore previous pack for $id")
                }
                runCatching {
                    writeInstalled(previousEntries)
                    writeGrammarRegistry(previousEntries)
                    registeredTypes.remove(id)?.let { FileTypeManager.unregister(it) }
                    previousEntries.firstOrNull { it.id == id && it.enabled }?.let { registerLanguage(it) }
                }.onFailure { extensionContext.logError("Could not restore previous language registration: ${it.message}") }
                throw e
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun remove(id: String): String {
        val entries = readInstalled()
        val entry = entries.firstOrNull { it.id.equals(id, true) }
            ?: return "Language '$id' is not installed.\n"
        registeredTypes.remove(entry.id)?.let { FileTypeManager.unregister(it) }
        File(grammarDir, entry.id).deleteRecursively()
        val remaining = entries.filterNot { it.id == entry.id }
        writeInstalled(remaining)
        writeGrammarRegistry(remaining)
        return "Removed ${entry.title}.\n"
    }

    private fun setEnabled(id: String, enabled: Boolean): String {
        val entries = readInstalled().toMutableList()
        val index = entries.indexOfFirst { it.id.equals(id, true) }
        if (index < 0) return "Language '$id' is not installed.\n"
        val updated = entries[index].copy(enabled = enabled)
        entries[index] = updated
        writeInstalled(entries)
        if (enabled) registerLanguage(updated) else {
            registeredTypes.remove(updated.id)?.let { FileTypeManager.unregister(it) }
            writeGrammarRegistry(entries)
        }
        return "${updated.title} ${if (enabled) "enabled" else "disabled"}.\n"
    }

    private fun update(id: String?): String {
        val entries = readInstalled()
        val targets = if (id == null) entries else entries.filter { it.id.equals(id, true) }
        if (targets.isEmpty()) return if (id == null) "No installed languages to update.\n" else "Language '$id' is not installed.\n"
        return targets.joinToString("") { install(it.id) }
    }

    private fun listLanguages(): String {
        val entries = readInstalled()
        if (entries.isEmpty()) return "No languages installed yet. Try: orange search astro\n"
        return buildString {
            append("Installed languages (${entries.size}):\n")
            entries.forEach { entry ->
                val state = if (entry.enabled) "[x]" else "[ ]"
                val extensions = entry.extensions.joinToString { ".$it" }
                append("  $state ${entry.id} — ${entry.title} ($extensions)\n")
            }
        }
    }

    private fun info(id: String): String {
        val item = catalog().firstOrNull { it.stringOrNull("id").equals(id, true) }
            ?: return "Unknown language '$id'.\n"
        val installed = readInstalled().firstOrNull { it.id.equals(id, true) }
        val extensions = item.getAsJsonArray("extensions").joinToString { ".${it.asString.removePrefix(".")}" }
        val state = when {
            installed == null -> "not installed"
            installed.enabled -> "enabled"
            else -> "disabled"
        }
        return """${item.string("name")} (${item.string("id")})
Scope: ${item.string("scopeName")}
Extensions: $extensions
Version: ${item.stringOrNull("version") ?: "1.0.0"}
Status: $state
"""
    }

    private fun status(): String {
        val entries = readInstalled()
        return """Orange 1.0.0
Available languages: ${catalog().size}
Installed languages: ${entries.size}
Enabled languages: ${entries.count { it.enabled }}
CLI: ${if (cliFile.canExecute()) "installed" else "missing"}
Registry: GitHub online catalog
"""
    }

    private fun safeAssetName(value: String): String? {
        if (!value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")) || value == "." || value == "..") return null
        return value
    }

    private fun restoreInstalledLanguages() {
        val entries = readInstalled()
        writeGrammarRegistry(entries)
        entries.filter { it.enabled }.forEach { entry ->
            runCatching {
                val type = OrangeFileType(entry)
                FileTypeManager.register(type)
                registeredTypes[entry.id] = type
            }.onFailure {
                extensionContext.logWarn("Could not restore language ${entry.id}: ${it.message}")
            }
        }
    }

    private fun registerLanguage(entry: InstalledLanguage) {
        writeGrammarRegistry(readInstalled())
        registeredTypes.remove(entry.id)?.let { FileTypeManager.unregister(it) }
        val type = OrangeFileType(entry)
        FileTypeManager.register(type)
        registeredTypes[entry.id] = type
    }

    private fun grammarDefinition(entry: InstalledLanguage): JsonObject =
        JsonObject().apply {
            addProperty("grammar", entry.grammar)
            addProperty("name", entry.id)
            addProperty("scopeName", entry.scope)
            entry.configuration?.let { addProperty("languageConfiguration", it) }
        }

    private fun writeGrammarRegistry(entries: List<InstalledLanguage>) {
        val definition = JsonObject().apply {
            add("languages", JsonArray().apply {
                entries.filter { it.enabled }.forEach { add(grammarDefinition(it)) }
            })
        }
        val registryFile = File(data, "registry/languages.json")
        registryFile.parentFile?.mkdirs()
        replaceFileAtomically(registryFile, gson.toJson(definition), "languages.json")
        GrammarRegistry.getInstance().loadGrammars("orange/registry/languages.json")
    }

    private fun readInstalled(): List<InstalledLanguage> {
        if (!metadataFile.isFile) return emptyList()
        return runCatching {
            gson.fromJson<List<InstalledLanguage>>(
                metadataFile.readText(),
                object : TypeToken<List<InstalledLanguage>>() {}.type
            ) ?: emptyList()
        }.getOrElse {
            extensionContext.logWarn("Installed language registry is invalid: ${it.message}")
            emptyList()
        }
    }

    private fun writeInstalled(entries: List<InstalledLanguage>) {
        metadataFile.parentFile?.mkdirs()
        replaceFileAtomically(metadataFile, gson.toJson(entries), "installed.json")
    }

    private fun replaceFileAtomically(target: File, content: String, temporaryName: String) {
        val parent = target.parentFile ?: throw IllegalStateException("Missing parent directory for ${target.name}")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Could not create ${parent.path}")
        val temporary = File(parent, ".$temporaryName.${Thread.currentThread().id}.tmp")
        val backup = File(parent, ".$temporaryName.backup")
        temporary.writeText(content)
        backup.delete()
        var backedUp = false
        try {
            if (target.exists()) {
                if (!target.renameTo(backup)) throw IllegalStateException("Could not preserve ${target.name}")
                backedUp = true
            }
            if (!temporary.renameTo(target)) throw IllegalStateException("Could not publish ${target.name}")
            backup.delete()
        } catch (e: Exception) {
            target.delete()
            if (backedUp && backup.exists() && !backup.renameTo(target)) {
                extensionContext.logError("Could not restore ${target.name} after failed write")
            }
            throw e
        } finally {
            temporary.delete()
        }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString
            ?: throw IllegalArgumentException("Missing '$key' in languages.json")

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}
