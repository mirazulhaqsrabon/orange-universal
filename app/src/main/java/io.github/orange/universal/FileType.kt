package io.github.orange.universal

import com.rk.file.FileType
import com.rk.icons.Icon

/**
 * Xed file type backed by a dynamically installed ULM language pack.
 */
internal class UlmFileType(
    private val language: InstalledLanguage
) : FileType {
    override val extensions: List<String> = language.extensions
    override val textmateScope: String = language.scope
    override val icon: Icon = Icon.TextIcon(language.title.take(1).uppercase())
    override val name: String = language.id
    override val title: String = language.title
}
