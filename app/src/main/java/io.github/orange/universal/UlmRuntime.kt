package io.github.orange.universal

import android.content.res.Resources
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipInputStream

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
        if (!requestedPath.startsWith("ulm/")) {
            null
        } else {
            runCatching {
                val root = data.canonicalFile
                val file = File(root, requestedPath.removePrefix("ulm/")).canonicalFile
                if (file.path.startsWith(root.path + File.separator) && file.isFile) file.inputStream() else null
            }.getOrNull()
        }
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
                    requestDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.lastModified() }?.forEach { request ->
                        if (!running) return@forEach
                        processRequest(request)
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
BASE="${HOME:-/home}/.local/share/ulm/bridge"
REQ="$BASE/requests"
RES="$BASE/responses"
mkdir -p "$REQ" "$RES"
ID="$$_$(date +%s)"
OUT="$RES/$ID"
TMP="$REQ/.$ID.tmp"
printf '%s\n' "$@" > "$TMP"
mv "$TMP" "$REQ/$ID"
COUNT=0
while [ ! -f "$OUT" ]; do
  COUNT=$((COUNT + 1))
  if [ "$COUNT" -ge 3600 ]; then
    rm -f "$REQ/$ID"
    echo "ulm: extension did not respond. Make sure Universal Language Manager is enabled in Xed."
    exit 1
  fi
  sleep 0.1
done
cat "$OUT"
rm -f "$OUT"
"""
        cliFile.parentFile?.mkdirs()
        cliFile.writeText(script)
        cliFile.setExecutable(true, false)
    }

    private fun processRequest(request: File) {
        val response = File(responseDir, request.name)
        try {
            val args = request.readLines().filter { it.isNotEmpty() }
            val result = handle(args)
            val temporary = File(responseDir, ".${request.name}.tmp")
            temporary.writeText(result)
            if (!temporary.renameTo(response)) throw IllegalStateException("Could not publish command response")
        } catch (e: Exception) {
            response.writeText("ulm: error: ${e.message ?: e.javaClass.simpleName}\n")
        } finally {
            request.delete()
        }
    }

    private fun handle(args: List<String>): String {
        if (args.isEmpty()) return help()
        return when (args[0].lowercase(Locale.ROOT)) {
            "help", "--help", "-h" -> help()
            "search" -> if (args.size < 2) "Usage: ulm search <language or keyword>\n" else search(args.drop(1).joinToString(" "))
            "install", "add" -> if (args.size != 2) "Usage: ulm install <extension-id or language>\n" else install(args[1])
            "remove", "uninstall" -> if (args.size != 2) "Usage: ulm remove <language-id>\n" else remove(args[1])
            "list", "ls" -> listLanguages()
            "info" -> if (args.size != 2) "Usage: ulm info <language-id>\n" else info(args[1])
            "update" -> update(args.getOrNull(1))
            "enable" -> if (args.size != 2) "Usage: ulm enable <language-id>\n" else setEnabled(args[1], true)
            "disable" -> if (args.size != 2) "Usage: ulm disable <language-id>\n" else setEnabled(args[1], false)
            "status" -> status()
            "version", "--version", "-v" -> "Universal Language Manager 1.0.0\n"
            else -> "Unknown command: ${args[0]}\nRun 'ulm help' for usage.\n"
        }
    }

    private fun help() = """Universal Language Manager (ULM) 1.0.0

Usage:
  ulm help                         Show this help
  ulm search <query>               Search Open VSX for TextMate language packs
  ulm install <id or language>     Download and register a language grammar
  ulm remove <language-id>         Remove an installed language
  ulm list                         List installed languages
  ulm info <language-id>           Show language details
  ulm update [language-id]         Update one or all installed language packs
  ulm enable <language-id>         Enable a language file type
  ulm disable <language-id>        Disable a language file type
  ulm status                       Show ULM status
  ulm version                      Show version

Examples:
  ulm search python
  ulm install ms-python.python
  ulm list
  ulm remove python

Packages are downloaded from Open VSX. An internet connection is required.
"""

    private fun search(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("https://open-vsx.org/api/-/search?query=$encoded&size=12")
        val extensions = json.getAsJsonArray("extensions") ?: JsonArray()
        if (extensions.size() == 0) return "No extensions found for '$query'.\n"
        return buildString {
            append("Open VSX results for '$query':\n\n")
            extensions.forEachIndexed { index, item ->
                val obj = item.asJsonObject
                val namespace = obj.string("namespace")
                val name = obj.string("name")
                append("${index + 1}. $namespace.$name")
                obj.stringOrNull("displayName")?.let { append(" — $it") }
                obj.stringOrNull("description")?.take(110)?.let { append("\n   $it") }
                append('\n')
            }
            append("\nInstall with: ulm install <namespace.extension>\n")
        }
    }

    private fun install(query: String): String {
        val selected = resolveExtension(query)
            ?: return "No Open VSX extension matched '$query'. Try: ulm search $query\n"
        val namespace = selected.first
        val packageName = selected.second
        val metadata = getJson("https://open-vsx.org/api/$namespace/$packageName")
        val version = metadata.string("version")
        val downloadUrl = metadata.getAsJsonObject("files")?.stringOrNull("download")
            ?: return "Open VSX did not provide a download URL for $namespace.$packageName.\n"
        val archive = getBytes(downloadUrl, MAX_PACKAGE_BYTES)
        val packageJsonBytes = readZipEntry(archive, "extension/package.json")
            ?: return "Invalid VSIX: extension/package.json is missing.\n"
        val packageJson = JsonParser.parseString(String(packageJsonBytes, Charsets.UTF_8)).asJsonObject
        val contributes = packageJson.getAsJsonObject("contributes")
            ?: return "This extension does not contribute language grammars.\n"
        val grammarArray = contributes.getAsJsonArray("grammars")
            ?: return "This extension does not contribute TextMate grammars.\n"
        val languages = contributes.getAsJsonArray("languages") ?: JsonArray()
        val extensionMap = mutableMapOf<String, List<String>>()
        val titleMap = mutableMapOf<String, String>()
        languages.forEach { element ->
            val language = element.asJsonObject
            val id = language.stringOrNull("id") ?: return@forEach
            val exts = language.getAsJsonArray("extensions")?.mapNotNull {
                it.asString.removePrefix(".").takeIf { ext -> ext.matches(Regex("[A-Za-z0-9_+-]+")) }
            }.orEmpty()
            extensionMap[id] = exts
            titleMap[id] = language.getAsJsonArray("aliases")?.firstOrNull()?.asString ?: id
        }

        val selectedGrammars = grammarArray.mapNotNull { it.asJsonObject }.filter {
            it.has("scopeName") && it.has("path")
        }
        if (selectedGrammars.isEmpty()) return "No valid TextMate grammar definitions were found.\n"

        val files = unzipRelevantFiles(archive, selectedGrammars, languages)
        if (files.isEmpty()) return "The VSIX contains no readable grammar files.\n"
        val installed = readInstalled().toMutableList()
        val added = mutableListOf<InstalledLanguage>()

        selectedGrammars.forEachIndexed { index, grammarDef ->
            val scope = grammarDef.string("scopeName")
            val languageId = grammarDef.stringOrNull("language")
                ?: scope.substringAfterLast('.').ifBlank { packageName.lowercase(Locale.ROOT) }
            val id = languageId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._+-]"), "-")
            val grammarRel = safeRelativePath(grammarDef.string("path")) ?: return@forEachIndexed
            val grammarBytes = files["extension/$grammarRel"] ?: return@forEachIndexed
            val languageConfigRel = languages.firstOrNull { it.asJsonObject.stringOrNull("id") == languageId }
                ?.asJsonObject?.stringOrNull("configuration")?.let(::safeRelativePath)
            val configBytes = languageConfigRel?.let { files["extension/$it"] }
            val languageFolder = File(grammarDir, id)
            languageFolder.deleteRecursively()
            languageFolder.mkdirs()
            val grammarName = File(grammarRel).name
            File(languageFolder, grammarName).writeBytes(grammarBytes)
            val grammarPath = "ulm/grammars/$id/$grammarName"
            var configPath: String? = null
            if (configBytes != null && languageConfigRel != null) {
                val configName = File(languageConfigRel).name
                File(languageFolder, configName).writeBytes(configBytes)
                configPath = "ulm/grammars/$id/$configName"
            }
            val entry = InstalledLanguage(
                id = id,
                title = titleMap[languageId] ?: packageJson.stringOrNull("displayName") ?: languageId,
                scope = scope,
                extensions = extensionMap[languageId].orEmpty(),
                grammar = grammarPath,
                configuration = configPath,
                namespace = namespace,
                packageName = packageName,
                version = version
            )
            installed.removeAll { it.id == id }
            installed.add(entry)
            added.add(entry)
        }
        if (added.isEmpty()) return "No usable grammar files were found in $namespace.$packageName.\n"
        writeInstalled(installed)
        added.forEach(::registerLanguage)
        return "Installed $namespace.$packageName@$version\n" +
            added.joinToString("") { "  • ${it.title} (${it.id}) — ${it.extensions.joinToString { ext -> ".$ext" }.ifBlank { "no file extensions declared" }}\n" } +
            "Restart or reopen the affected editor tab if highlighting does not appear immediately.\n"
    }

    private fun resolveExtension(query: String): Pair<String, String>? {
        val exact = query.trim()
        if (exact.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_.-]+"))) {
            val parts = exact.split('.', limit = 2)
            return parts[0] to parts[1]
        }
        val encoded = URLEncoder.encode(exact, "UTF-8")
        val json = getJson("https://open-vsx.org/api/-/search?query=$encoded&size=30")
        val extensions = json.getAsJsonArray("extensions") ?: return null
        val candidate = extensions.firstOrNull { element ->
            val obj = element.asJsonObject
            obj.stringOrNull("name").equals(exact, true) ||
                obj.stringOrNull("displayName").equals(exact, true) ||
                "${obj.stringOrNull("namespace")}.${obj.stringOrNull("name")}".equals(exact, true)
        } ?: extensions.firstOrNull() ?: return null
        val obj = candidate.asJsonObject
        val namespace = obj.stringOrNull("namespace") ?: return null
        val name = obj.stringOrNull("name") ?: return null
        return namespace to name
    }

    private fun unzipRelevantFiles(
        archive: ByteArray,
        grammarDefs: List<com.google.gson.JsonObject>,
        languages: JsonArray
    ): Map<String, ByteArray> {
        val wanted = mutableSetOf("extension/package.json")
        grammarDefs.forEach { it.stringOrNull("path")?.let(::safeRelativePath)?.let { path -> wanted.add("extension/$path") } }
        languages.forEach { it.asJsonObject.stringOrNull("configuration")?.let(::safeRelativePath)?.let { path -> wanted.add("extension/$path") } }
        val result = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && (name in wanted || name.startsWith("extension/syntaxes/"))) {
                    val bytes = zip.readBounded(MAX_SINGLE_FILE_BYTES)
                    total += bytes.size
                    if (total > MAX_EXTRACTED_BYTES) throw IllegalArgumentException("VSIX grammar payload exceeds size limit")
                    result[name] = bytes
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun readZipEntry(archive: ByteArray, target: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == target) return zip.readBounded(MAX_SINGLE_FILE_BYTES)
                zip.closeEntry()
            }
        }
    }

    private fun remove(id: String): String {
        val entries = readInstalled().toMutableList()
        val matches = entries.filter { it.id.equals(id, true) }
        if (matches.isEmpty()) return "Language '$id' is not installed.\n"
        matches.forEach { entry ->
            registeredTypes.remove(entry.id)?.let { FileTypeManager.unregister(it) }
            File(grammarDir, entry.id).deleteRecursively()
        }
        writeInstalled(entries - matches.toSet())
        return "Removed: ${matches.joinToString { it.id }}\nNote: TextMate keeps already-loaded grammar data in memory until Xed is restarted.\n"
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
        }
        return "${updated.title} ${if (enabled) "enabled" else "disabled"}.\n"
    }

    private fun update(id: String?): String {
        val entries = readInstalled()
        val targets = if (id == null) entries else entries.filter { it.id.equals(id, true) }
        if (targets.isEmpty()) return if (id == null) "No installed languages to update.\n" else "Language '$id' is not installed.\n"
        val output = StringBuilder()
        targets.map { it.namespace + "." + it.packageName }.distinct().forEach { packageId ->
            output.append(install(packageId))
        }
        return output.toString()
    }

    private fun listLanguages(): String {
        val entries = readInstalled()
        if (entries.isEmpty()) return "No languages installed yet. Try: ulm search python\n"
        return buildString {
            append("Installed languages (${entries.size}):\n")
            entries.forEach { append("  ${if (it.enabled) "[x]" else "[ ]"} ${it.id} — ${it.title} (${it.extensions.joinToString { ext -> ".$ext" }})\n") }
        }
    }

    private fun info(id: String): String {
        val entry = readInstalled().firstOrNull { it.id.equals(id, true) }
            ?: return "Language '$id' is not installed.\n"
        return """${entry.title} (${entry.id})
Scope: ${entry.scope}
Extensions: ${entry.extensions.joinToString { ".$it" }.ifBlank { "none" }}
Package: ${entry.namespace}.${entry.packageName}
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
Registry: ${if (metadataFile.isFile) "available" else "empty"}
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
                add(JsonObject().apply {
                    addProperty("grammar", entry.grammar)
                    addProperty("name", entry.id)
                    addProperty("scopeName", entry.scope)
                    entry.configuration?.let { addProperty("languageConfiguration", it) }
                })
            })
        }
        definitionFile.writeText(gson.toJson(definition))
        GrammarRegistry.getInstance().loadGrammars("ulm/registry/${entry.id}.json")
        registeredTypes.remove(entry.id)?.let { FileTypeManager.unregister(it) }
        if (entry.extensions.isNotEmpty()) {
            val type = DynamicFileType(entry, extensionContext.resources)
            FileTypeManager.register(type)
            registeredTypes[entry.id] = type
        }
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
        metadataFile.writeText(gson.toJson(entries))
    }

    private fun getJson(address: String): JsonObject {
        val bytes = getBytes(address, MAX_RESPONSE_BYTES)
        return JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
    }

    private fun getBytes(address: String, maxBytes: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "UniversalLanguageManager/1.0 (Xed-Editor extension)")
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                throw IllegalStateException("HTTP $code from $address ${message.orEmpty()}")
            }
            connection.inputStream.use { return it.readBounded(maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeRelativePath(raw: String): String? {
        val normalized = raw.removePrefix("./").replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." } || normalized.contains(':')) return null
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: throw IllegalArgumentException("Missing '$key' in Open VSX response")

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
            if (total > limit) throw IllegalArgumentException("Download exceeds size limit")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private class DynamicFileType(
        private val entry: InstalledLanguage,
        resources: Resources
    ) : FileType {
        override val extensions: List<String> = entry.extensions
        override val textmateScope: String = entry.scope
        override val icon: Icon = Icon.ExternalResourceIcon(android.R.drawable.ic_menu_manage, resources)
        override val name: String = entry.id
        override val title: String = entry.title
    }

    companion object {
        private const val MAX_PACKAGE_BYTES = 25 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_SINGLE_FILE_BYTES = 8 * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 20 * 1024 * 1024
    }
}
