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

internal class UlmRuntime(
    private val extensionContext: com.rk.extension.ExtensionContext
) {
    private val gson = Gson()
    private val privateRoot = extensionContext.appContext.filesDir.parentFile
        ?: throw IllegalStateException("Xed private directory is unavailable")
    private val home = File(privateRoot, "local/home")
    private val data = File(home, ".local/share/ulm")
    private val requestDir = File(data, "bridge/requests")
    private val responseDir = File(data, "bridge/responses")
    private val grammarDir = File(data, "grammars")
    private val metadataFile = File(data, "installed.json")
    private val cliFile = File(privateRoot, "local/bin/ulm")
    private val registeredTypes = mutableMapOf<String, FileType>()
    @Volatile private var running = false
    private var worker: Thread? = null

    private val resolver = FileResolver { requestedPath ->
        if (!requestedPath.startsWith("ulm/")) null else runCatching {
            val root = data.canonicalFile
            val file = File(root, requestedPath.removePrefix("ulm/")).canonicalFile
            if (file.path.startsWith(root.path + File.separator) && file.isFile) file.inputStream() else null
        }.getOrNull()
    }

    fun start() {
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
                        ?.sortedBy { it.lastModified() }?.forEach { request ->
                            if (running) processRequest(request)
                        }
                    Thread.sleep(120)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    extensionContext.logError("ULM bridge error: ${e.message}")
                }
            }
        }, "ulm-command-bridge").apply {
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
BASE="@@DOLLAR@@{HOME:-/home}/.local/share/ulm/bridge"
REQ="@@DOLLAR@@BASE/requests"
RES="@@DOLLAR@@BASE/responses"
mkdir -p "@@DOLLAR@@REQ" "@@DOLLAR@@RES"
ID="@@DOLLAR@@@@DOLLAR@@-@@DOLLAR@@(date +%s%N)"
OUT="@@DOLLAR@@RES/@@DOLLAR@@ID"
TMP="@@DOLLAR@@REQ/.@@DOLLAR@@ID.tmp"
printf '%s\n' "@@DOLLAR@@@" > "@@DOLLAR@@TMP"
mv "@@DOLLAR@@TMP" "@@DOLLAR@@REQ/@@DOLLAR@@ID"
COUNT=0
while [ ! -f "@@DOLLAR@@OUT" ]; do
  COUNT=@@DOLLAR@@((COUNT + 1))
  if [ "@@DOLLAR@@COUNT" -ge 3600 ]; then
    rm -f "@@DOLLAR@@REQ/@@DOLLAR@@ID"
    echo "ulm: extension did not respond. Make sure ULM is enabled in Xed."
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
        val response = File(responseDir, ".${request.name}.tmp")
        try {
            val args = request.readLines().filter { it.isNotEmpty() }
            response.writeText(handle(args))
            val published = File(responseDir, request.name)
            if (published.exists()) published.delete()
            if (!response.renameTo(published)) throw IllegalStateException("Could not publish command response")
        } catch (e: Exception) {
            File(responseDir, request.name).writeText("ulm: error: ${e.message ?: e.javaClass.simpleName}\n")
        } finally {
            request.delete()
        }
    }

    private fun handle(args: List<String>): String {
        if (args.isEmpty()) return help()
        return when (args[0].lowercase(Locale.ROOT)) {
            "help", "--help", "-h" -> help()
            "search" -> search(args.drop(1).joinToString(" "))
            "install", "add" -> if (args.size != 2) "Usage: ulm install <language-id>\n" else install(args[1])
            "remove", "uninstall" -> if (args.size != 2) "Usage: ulm remove <language-id>\n" else remove(args[1])
            "list", "ls" -> listLanguages()
            "info" -> if (args.size != 2) "Usage: ulm info <language-id>\n" else info(args[1])
            "update" -> update(args.getOrNull(1))
            "enable" -> if (args.size != 2) "Usage: ulm enable <language-id>\n" else setEnabled(args[1], true)
            "disable" -> if (args.size != 2) "Usage: ulm disable <language-id>\n" else setEnabled(args[1], false)
            "status" -> status()
            "test-online", "online" -> testOnlineDownload()
            "version", "--version", "-v" -> "Orange 1.0.0\n"
            else -> "Unknown command: ${args[0]}\nRun 'ulm help' for usage.\n"
        }
    }

    private fun help() = """Universal Language Manager (ULM) 1.0.0

Usage:
  ulm help                    Show command help
  ulm search <query>          Search online language catalog
  ulm install <language-id>   Download and install a language
  ulm remove <language-id>    Remove an installed language
  ulm list                    List installed languages
  ulm info <language-id>      Show language details
  ulm update [language-id]    Download and reinstall language assets
  ulm enable <language-id>    Enable a language file type
  ulm disable <language-id>   Disable a language file type
  ulm status                  Show manager status
  ulm test-online             Test GitHub catalog and asset download
  ulm version                 Show version

Example:
  ulm search astro
  ulm install astro
  ulm list
  ulm remove astro

Language packs are downloaded from the ULM GitHub repository when available.
"""

    private val remoteCatalogUrl =
        "https://raw.githubusercontent.com/mirazulhaqsrabon/orange-universal/feature/orange-menu-download/language-packs/languages.json"

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
        connection.setRequestProperty("User-Agent", "Universal-Language-Manager/1.1")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code while downloading catalog")
            if (connection.contentLengthLong > 2L * 1024L * 1024L) throw IllegalStateException("Remote catalog exceeds 2 MB")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadRemoteAsset(languageId: String, assetName: String, destination: File) {
        if (!languageId.matches(Regex("[a-z0-9][a-z0-9._+-]*")) || safeAssetName(assetName) == null) {
            throw IllegalArgumentException("Invalid remote asset path")
        }
        val url = "https://raw.githubusercontent.com/mirazulhaqsrabon/orange-universal/feature/orange-menu-download/language-packs/" + languageId + "/" + assetName
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 30000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Universal-Language-Manager/1.1")
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
        if (query.isBlank()) return "Usage: ulm search <query>\n"
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
                append("  Install: ulm install $id\n")
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
        if (!id.matches(Regex("[a-z0-9][a-z0-9._+-]*"))) return "Invalid language id.\n"
        val metadata = catalog().firstOrNull { it.stringOrNull("id").equals(id, true) }
            ?: return "Language '$id' is not available in the online GitHub catalog. Try: ulm search astro\n"

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
        val staging = File(grammarDir, ".$id-staging")
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
                configurationPath = "ulm/grammars/$id/$configName"
            }

            val destination = File(grammarDir, id)
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) throw IllegalStateException("Could not install language assets")

            val entry = InstalledLanguage(
                id = id,
                title = metadata.string("name"),
                scope = scope,
                extensions = extensions,
                grammar = "ulm/grammars/$id/$grammarName",
                configuration = configurationPath,
                version = metadata.stringOrNull("version") ?: "1.0.0",
                enabled = true
            )
            val entries = readInstalled().toMutableList()
            entries.removeAll { it.id == id }
            entries.add(entry)
            writeInstalled(entries)
            registerLanguage(entry)
            return "Installed ${entry.title} ${entry.version}\n  Scope: ${entry.scope}\n  Files: ${entry.extensions.joinToString { ".$it" }}\n"
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
        if (entries.isEmpty()) return "No languages installed yet. Try: ulm search astro\n"
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
                val type = UlmFileType(entry)
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
        val type = UlmFileType(entry)
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
        val temporary = File(registryFile.parentFile, "languages.json.tmp")
        temporary.writeText(gson.toJson(definition))
        if (registryFile.exists() && !registryFile.delete()) throw IllegalStateException("Could not replace grammar registry")
        if (!temporary.renameTo(registryFile)) throw IllegalStateException("Could not publish grammar registry")
        GrammarRegistry.getInstance().loadGrammars("ulm/registry/languages.json")
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
        val temporary = File(metadataFile.parentFile, "installed.json.tmp")
        temporary.writeText(gson.toJson(entries))
        if (metadataFile.exists() && !metadataFile.delete()) throw IllegalStateException("Could not replace installed language list")
        if (!temporary.renameTo(metadataFile)) throw IllegalStateException("Could not save installed language list")
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString
            ?: throw IllegalArgumentException("Missing '$key' in languages.json")

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}
