package io.github.orange.universal

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.rk.file.FileType
import com.rk.file.FileTypeManager
import com.rk.icons.Icon
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.FileResolver
import java.io.ByteArrayOutputStream
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
    val namespace: String,
    val packageName: String,
    val version: String,
    val enabled: Boolean = true,
    val injectionGrammar: String? = null,
    val injectionScope: String? = null
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
        extensionContext.logInfo("Universal Language Manager is ready. Run 'ulm help' in Xed Ubuntu terminal.")
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
BASE="${'$'}{HOME:-/home}/.local/share/ulm/bridge"
REQ="${'$'}BASE/requests"
RES="${'$'}BASE/responses"
mkdir -p "${'$'}REQ" "${'$'}RES"
ID="${'$'}${'$'}_${'$'}(date +%s)"
OUT="${'$'}RES/${'$'}ID"
TMP="${'$'}REQ/.${'$'}ID.tmp"
printf "%s\\n" "${'$'}@" > "${'$'}TMP"
mv "${'$'}TMP" "${'$'}REQ/${'$'}ID"
COUNT=0
while [ ! -f "${'$'}OUT" ]; do
  COUNT=${'$'}((COUNT + 1))
  if [ "${'$'}COUNT" -ge 3600 ]; then
    rm -f "${'$'}REQ/${'$'}ID"
    echo "ulm: extension did not respond. Make sure ULM is enabled in Xed."
    exit 1
  fi
  sleep 0.1
done
cat "${'$'}OUT"
rm -f "${'$'}OUT"
"""
        cliFile.parentFile?.mkdirs()
        cliFile.writeText(script)
        cliFile.setExecutable(true, false)
    }

    private fun processRequest(request: File) {
        val response = File(responseDir, request.name)
        try {
            val args = request.readLines().filter { it.isNotEmpty() }
            val temporary = File(responseDir, ".${request.name}.tmp")
            temporary.writeText(handle(args))
            if (!temporary.renameTo(response)) throw IllegalStateException("Could not publish command response")
        } catch (e: Exception) {
            response.writeText("ulm: error: ${e.message ?: e.javaClass.simpleName}\\n")
        } finally {
            request.delete()
        }
    }

    private fun handle(args: List<String>): String {
        if (args.isEmpty()) return help()
        return when (args[0].lowercase(Locale.ROOT)) {
            "help", "--help", "-h" -> help()
            "search" -> search(args.drop(1).joinToString(" "))
            "install", "add" -> if (args.size != 2) "Usage: ulm install <language-id>\\n" else install(args[1])
            "remove", "uninstall" -> if (args.size != 2) "Usage: ulm remove <language-id>\\n" else remove(args[1])
            "list", "ls" -> listLanguages()
            "info" -> if (args.size != 2) "Usage: ulm info <language-id>\\n" else info(args[1])
            "update" -> update(args.getOrNull(1))
            "enable" -> if (args.size != 2) "Usage: ulm enable <language-id>\\n" else setEnabled(args[1], true)
            "disable" -> if (args.size != 2) "Usage: ulm disable <language-id>\\n" else setEnabled(args[1], false)
            "status" -> status()
            "version", "--version", "-v" -> "Universal Language Manager 1.0.0\\n"
            else -> "Unknown command: ${args[0]}\\nRun 'ulm help' for usage.\\n"
        }
    }

    private fun help() = """Universal Language Manager (ULM) 1.0.0

Usage:
  ulm help                    Show this help
  ulm search <query>          Search your language registry
  ulm install <language-id>   Install a language pack
  ulm remove <language-id>    Remove an installed language
  ulm list                    List installed languages
  ulm info <language-id>      Show language details
  ulm update [language-id]    Update one or all installed packs
  ulm enable <language-id>    Enable a language file type
  ulm disable <language-id>   Disable a language file type
  ulm status                  Show ULM status
  ulm version                 Show version

Available pack:
  astro — Astro syntax highlighting for .astro files

Examples:
  ulm search astro
  ulm install astro
  ulm list
  ulm update astro
  ulm remove astro

Packs are hosted in the ULM registry on GitHub. No Open VSX connection is used.
"""

    private fun fetchRegistry(): JsonObject =
        getJson("$REGISTRY_BASE/registry.json")

    private fun search(query: String): String {
        if (query.isBlank()) return "Usage: ulm search <query>\\n"
        val languages = fetchRegistry().getAsJsonArray("languages") ?: JsonArray()
        val matches = languages.filter { element ->
            val obj = element.asJsonObject
            obj.stringOrNull("id").orEmpty().contains(query, true) ||
                obj.stringOrNull("name").orEmpty().contains(query, true) ||
                obj.stringOrNull("description").orEmpty().contains(query, true)
        }
        if (matches.isEmpty()) return "No language packs found for '$query'.\\n"
        return buildString {
            append("ULM registry results:\\n\\n")
            matches.forEach { element ->
                val obj = element.asJsonObject
                append("• ${obj.stringOrNull("id")} — ${obj.stringOrNull("name")}\\n")
                obj.stringOrNull("description")?.let { append("  $it\\n") }
                append("  Install: ulm install ${obj.stringOrNull("id")}\\n")
            }
        }
    }

    private fun install(idInput: String): String {
        val id = idInput.lowercase(Locale.ROOT)
        if (!id.matches(Regex("[a-z0-9][a-z0-9._+-]*"))) return "Invalid language id.\\n"
        val registry = fetchRegistry()
        val item = registry.getAsJsonArray("languages")?.firstOrNull {
            it.asJsonObject.stringOrNull("id").equals(id, true)
        }?.asJsonObject ?: return "Language '$id' is not in the ULM registry. Try: ulm search astro\\n"
        val metadataPath = safeRelativePath(item.string("metadata"))
            ?: throw IllegalArgumentException("Registry contains an unsafe metadata path")
        val metadata = getJson("$REGISTRY_BASE/$metadataPath")
        if (metadata.string("id") != id) throw IllegalArgumentException("Language metadata id does not match registry")
        val scope = metadata.string("scopeName")
        if (!scope.matches(Regex("[A-Za-z0-9_.+-]+"))) throw IllegalArgumentException("Invalid TextMate scope")
        val extensions = metadata.getAsJsonArray("extensions")?.mapNotNull { element ->
            element.asString.removePrefix(".").takeIf { it.matches(Regex("[A-Za-z0-9_+-]+")) }
        }.orEmpty()
        if (extensions.isEmpty()) throw IllegalArgumentException("Language pack has no valid file extensions")

        val folder = File(grammarDir, id)
        folder.deleteRecursively()
        folder.mkdirs()
        val grammarRel = safeRelativePath(metadata.string("grammar"))
            ?: throw IllegalArgumentException("Invalid grammar path")
        val grammarBytes = getBytes("$REGISTRY_BASE/languages/$id/$grammarRel", MAX_GRAMMAR_BYTES)
        val grammarJson = JsonParser.parseString(String(grammarBytes, Charsets.UTF_8)).asJsonObject
        if (grammarJson.stringOrNull("scopeName") != scope) {
            throw IllegalArgumentException("Grammar scope does not match metadata")
        }
        val grammarDestination = File(folder, grammarRel)
        grammarDestination.parentFile?.mkdirs()
        grammarDestination.writeBytes(grammarBytes)

        val configurationRel = metadata.stringOrNull("configuration")?.let(::safeRelativePath)
        var configurationPath: String? = null
        if (configurationRel != null) {
            val bytes = getBytes("$REGISTRY_BASE/languages/$id/$configurationRel", MAX_CONFIG_BYTES)
            val destination = File(folder, configurationRel)
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            configurationPath = "ulm/grammars/$id/$configurationRel"
        }

        var injectionPath: String? = null
        var injectionScope: String? = null
        metadata.getAsJsonArray("injections")?.firstOrNull()?.asJsonObject?.let { injection ->
            val rel = safeRelativePath(injection.string("grammar"))
                ?: throw IllegalArgumentException("Invalid injection grammar path")
            val bytes = getBytes("$REGISTRY_BASE/languages/$id/$rel", MAX_GRAMMAR_BYTES)
            val injectionJson = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            val expectedScope = injection.string("scopeName")
            if (injectionJson.stringOrNull("scopeName") != expectedScope) {
                throw IllegalArgumentException("Injection grammar scope does not match metadata")
            }
            val destination = File(folder, rel)
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            injectionPath = "ulm/grammars/$id/$rel"
            injectionScope = expectedScope
        }

        val entry = InstalledLanguage(
            id = id,
            title = metadata.string("name"),
            scope = scope,
            extensions = extensions,
            grammar = "ulm/grammars/$id/$grammarRel",
            configuration = configurationPath,
            namespace = OWNER,
            packageName = REPOSITORY,
            version = metadata.string("version"),
            enabled = true,
            injectionGrammar = injectionPath,
            injectionScope = injectionScope
        )
        val entries = readInstalled().toMutableList()
        entries.removeAll { it.id == id }
        entries.add(entry)
        writeInstalled(entries)
        registerLanguage(entry)
        return "Installed ${entry.title} ${entry.version}\\n  Scope: ${entry.scope}\\n  Files: ${entry.extensions.joinToString { ".$it" }}\\n  Source: $REPOSITORY\\n"
    }

    private fun remove(id: String): String {
        val entries = readInstalled()
        val matches = entries.filter { it.id.equals(id, true) }
        if (matches.isEmpty()) return "Language '$id' is not installed.\\n"
        matches.forEach { entry ->
            registeredTypes.remove(entry.id)?.let { FileTypeManager.unregister(it) }
            File(grammarDir, entry.id).deleteRecursively()
            File(data, "registry/${entry.id}.json").delete()
        }
        writeInstalled(entries - matches.toSet())
        return "Removed: ${matches.joinToString { it.id }}\\nRestart Xed to clear already-loaded TextMate grammar data.\\n"
    }

    private fun setEnabled(id: String, enabled: Boolean): String {
        val entries = readInstalled().toMutableList()
        val index = entries.indexOfFirst { it.id.equals(id, true) }
        if (index < 0) return "Language '$id' is not installed.\\n"
        val updated = entries[index].copy(enabled = enabled)
        entries[index] = updated
        writeInstalled(entries)
        if (enabled) registerLanguage(updated) else registeredTypes.remove(updated.id)?.let { FileTypeManager.unregister(it) }
        return "${updated.title} ${if (enabled) "enabled" else "disabled"}.\\n"
    }

    private fun update(id: String?): String {
        val entries = readInstalled()
        val targets = if (id == null) entries else entries.filter { it.id.equals(id, true) }
        if (targets.isEmpty()) return if (id == null) "No installed languages to update.\\n" else "Language '$id' is not installed.\\n"
        return targets.joinToString("") { install(it.id) }
    }

    private fun listLanguages(): String {
        val entries = readInstalled()
        if (entries.isEmpty()) return "No languages installed yet. Try: ulm search astro\\n"
        return buildString {
            append("Installed languages (${entries.size}):\\n")
            entries.forEach { append("  ${if (it.enabled) "[x]" else "[ ]"} ${it.id} — ${it.title} (${it.extensions.joinToString { ext -> ".$ext" }})\\n") }
        }
    }

    private fun info(id: String): String {
        val entry = readInstalled().firstOrNull { it.id.equals(id, true) }
            ?: return "Language '$id' is not installed.\\n"
        return """${entry.title} (${entry.id})
Scope: ${entry.scope}
Extensions: ${entry.extensions.joinToString { ".$it" }}
Source: $REPOSITORY
Version: ${entry.version}
Status: ${if (entry.enabled) "enabled" else "disabled"}
Grammar: ${entry.grammar}
"""
    }

    private fun status(): String {
        val entries = readInstalled()
        return """Universal Language Manager 1.0.0
Installed languages: ${entries.size}
Enabled languages: ${entries.count { it.enabled }}
CLI: ${if (cliFile.canExecute()) "installed" else "missing"}
Registry: self-hosted GitHub ($REPOSITORY)
"""
    }

    private fun restoreInstalledLanguages() {
        readInstalled().filter { it.enabled }.forEach { entry ->
            runCatching { registerLanguage(entry) }
                .onFailure { extensionContext.logWarn("Could not restore grammar ${entry.id}: ${it.message}") }
        }
    }

    private fun registerLanguage(entry: InstalledLanguage) {
        val definitionFile = File(data, "registry/${entry.id}.json")
        definitionFile.parentFile?.mkdirs()
        val definition = JsonObject().apply {
            add("languages", JsonArray().apply {
                add(grammarDefinition(entry.grammar, entry.id, entry.scope, entry.configuration))
                if (entry.injectionGrammar != null && entry.injectionScope != null) {
                    add(grammarDefinition(entry.injectionGrammar, "${entry.id}-mdx-injection", entry.injectionScope, null))
                }
            })
        }
        definitionFile.writeText(gson.toJson(definition))
        GrammarRegistry.getInstance().loadGrammars("ulm/registry/${entry.id}.json")
        registeredTypes.remove(entry.id)?.let { FileTypeManager.unregister(it) }
        val type = DynamicFileType(entry)
        FileTypeManager.register(type)
        registeredTypes[entry.id] = type
    }

    private fun grammarDefinition(grammar: String, name: String, scope: String, configuration: String?): JsonObject =
        JsonObject().apply {
            addProperty("grammar", grammar)
            addProperty("name", name)
            addProperty("scopeName", scope)
            configuration?.let { addProperty("languageConfiguration", it) }
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
        if (metadataFile.exists()) metadataFile.delete()
        if (!temporary.renameTo(metadataFile)) throw IllegalStateException("Could not save installed language list")
    }

    private fun getJson(address: String): JsonObject =
        JsonParser.parseString(String(getBytes(address, MAX_RESPONSE_BYTES), Charsets.UTF_8)).asJsonObject

    private fun getBytes(address: String, maxBytes: Int): ByteArray {
        val url = URL(address)
        if (url.protocol != "https" || url.host != "raw.githubusercontent.com") {
            throw IllegalArgumentException("Only the trusted self-hosted HTTPS registry is allowed")
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "UniversalLanguageManager/1.0")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code while reading ULM registry")
            connection.inputStream.use { return it.readBounded(maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeRelativePath(raw: String): String? {
        val normalized = raw.removePrefix("./").replace('\\\\', '/')
        val segments = normalized.split('/')
        if (normalized.startsWith("/") || normalized.contains(':') ||
            segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: throw IllegalArgumentException("Missing '$key' in ULM metadata")

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalArgumentException("Registry file exceeds size limit")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private class DynamicFileType(private val entry: InstalledLanguage) : FileType {
        override val extensions: List<String> = entry.extensions
        override val textmateScope: String = entry.scope
        override val icon: Icon = Icon.TextIcon("A")
        override val name: String = entry.id
        override val title: String = entry.title
    }

    companion object {
        private const val OWNER = "mirazulhaqsrabon"
        private const val REPOSITORY = "orange-universal"
        private const val REGISTRY_BASE = "https://raw.githubusercontent.com/mirazulhaqsrabon/orange-universal/work/ulm-runtime/ulm"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_GRAMMAR_BYTES = 8 * 1024 * 1024
        private const val MAX_CONFIG_BYTES = 512 * 1024
    }
}
