package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpFormat
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.xml.KdbxEntry
import dev.keething.totp.TotpResolver

/**
 * Applies KeePass's meaning to an entry of the file (Mac `EntryMapper`): which fields are the standard ones,
 * which hold the one-time code, how tags are written, the field types and the kind in its `CustomData`. A secret it does not keep (a duplicate field, the
 * code's fields once read, beside the copy [Entry.codeFields] keeps) is wiped.
 *
 * [binarySizes]: the sizes of the file's attachments, which `Ref`s point into. [iconIds]: the custom icons
 * the file has.
 */
internal class EntryMapper(private val binarySizes: List<Int>, private val iconIds: Set<String>) {
    fun map(raw: KdbxEntry): Entry {
        var title = ""
        var username = ""
        var password: Secret? = null
        var url = ""
        var notes = ""
        var notesProtected = false
        val totpFields = mutableMapOf<String, String>()
        val codeFields = mutableListOf<CustomField>()
        val customFields = mutableListOf<CustomField>()

        for (field in raw.fields) {
            when (field.key) {
                TITLE -> title = text(field.value)
                USERNAME -> username = text(field.value)
                PASSWORD -> {
                    password?.wipe()
                    password = when (val value = field.value) {
                        is FieldValue.Protected -> value.secret
                        is FieldValue.Plain -> Secret.of(value.text)
                    }
                }
                URL -> url = text(field.value)
                NOTES -> {
                    notes = text(field.value)
                    notesProtected = field.value is FieldValue.Protected
                }
                in TotpFormat.FIELD_NAMES -> {
                    codeFields += CustomField(field.key, kept(field.value))
                    totpFields[field.key] = text(field.value)
                }
                else -> customFields += CustomField(field.key, field.value)
            }
        }

        return Entry(
            id = raw.uuid,
            title = title,
            username = username,
            password = password ?: Secret.empty(),
            url = url,
            notes = notes,
            notesProtected = notesProtected,
            groupPath = raw.groupPath,
            groupId = raw.ancestorGroupUuids.lastOrNull() ?: "",
            tags = raw.tags.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() },
            customFields = customFields,
            fieldTypes = raw.customData[EntryFieldTypes.CUSTOM_DATA_KEY]?.let(EntryFieldTypes::decode).orEmpty(),
            entryType = raw.customData[EntryType.CUSTOM_DATA_KEY],
            totp = TotpResolver.resolve(totpFields),
            hasCodeFields = totpFields.isNotEmpty(),
            codeFields = codeFields,
            attachments = raw.attachments.map { Attachment(it.name, it.ref?.let(binarySizes::getOrNull)) },
            expires = raw.expires,
            expiryTime = raw.expiryTime,
            lastModified = raw.lastModified,
            created = raw.created,
            customIconId = raw.customIconUuid?.takeIf { it in iconIds },
        )
    }

    /**
     * A standard or code field's text. Such a field marked protected is revealed (as on the Mac) and its
     * secret wiped: only the password stays a secret among them. Notes say whether they were protected
     * ([Entry.notesProtected]); their text is a string all the same (SPEC §4).
     */
    private fun text(value: FieldValue): String = when (value) {
        is FieldValue.Plain -> value.text
        is FieldValue.Protected -> value.secret.reveal().also { value.secret.wipe() }
    }

    /** A code field's value kept as the file has it ([Entry.codeFields]): a protected one as a secret of its own. */
    private fun kept(value: FieldValue): FieldValue = when (value) {
        is FieldValue.Plain -> value
        is FieldValue.Protected -> FieldValue.Protected(value.secret.copy())
    }

    private companion object {
        const val TITLE = "Title"
        const val USERNAME = "UserName"
        const val PASSWORD = "Password"
        const val URL = "URL"
        const val NOTES = "Notes"
    }
}
