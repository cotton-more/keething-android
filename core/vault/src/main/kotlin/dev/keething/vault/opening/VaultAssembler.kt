package dev.keething.vault.opening

import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.xml.KdbxDocument

/**
 * Builds the vault from the file's document (Mac `VaultAssembler`): sets everything inside the recycle bin
 * group (at any depth) apart, maps the other entries, and sets the first one titled "Keething" apart as the Mac
 * app's settings entry. The templates group's entries and groups are set apart the same way (keething-spec
 * groups.templates AC1, AC2): the group `Meta/EntryTemplatesGroup` names, when it is not the root group nor the bin or
 * a group in it.
 */
internal object VaultAssembler {
    fun assemble(document: KdbxDocument, binarySizes: List<Int>): Vault {
        val mapper = EntryMapper(binarySizes, document.meta.customIcons.keys)
        val bin = document.meta.recycleBinUuid
        fun inBin(ancestors: List<String>) = bin.isNotEmpty() && bin in ancestors
        val templates = document.meta.templatesGroupUuid.takeIf { uuid ->
            document.groups.any { it.uuid == uuid && it.path.isNotEmpty() && uuid != bin && !inBin(it.ancestorUuids) }
        }
        fun inTemplates(ancestors: List<String>) = templates != null && templates in ancestors

        val (binned, notBinned) = document.entries.partition { inBin(it.ancestorGroupUuids) }
        val (templated, kept) = notBinned.partition { inTemplates(it.ancestorGroupUuids) }
        val entries = kept.map(mapper::map).toMutableList()
        val settingsIndex = entries.indexOfFirst { it.title.trim() == Vault.SETTINGS_TITLE }
        val settings = if (settingsIndex >= 0) entries.removeAt(settingsIndex) else null
        return Vault(
            entries = entries,
            settings = settings,
            groups = document.groups.filterNot { inBin(it.ancestorUuids) || it.uuid == templates || inTemplates(it.ancestorUuids) }.map { VaultGroup(it.uuid, it.name, it.path) },
            recycleBin = binned.map(mapper::map),
            recycleBinGroup = document.groups.firstOrNull { it.uuid == bin && bin.isNotEmpty() }?.let { VaultGroup(it.uuid, it.name, it.path) },
            templates = templated.map(mapper::map),
            templatesGroup = document.groups.firstOrNull { it.uuid == templates }?.let { VaultGroup(it.uuid, it.name, it.path) },
            recycleBinEnabled = document.meta.recycleBinEnabled,
            defaultUsername = document.meta.defaultUserName,
            customIcons = document.meta.customIcons,
            name = document.meta.databaseName,
            color = document.meta.color.ifEmpty { null },
            masterKeyChanged = document.meta.masterKeyChanged,
        )
    }
}
