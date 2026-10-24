package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.xml.KdbxEntry
import dev.keething.totp.TotpResolver

/**
 * Applies KeePass's meaning to an entry of the file (Mac `EntryMapper`): which fields are the standard ones,
 * which hold the one-time code, how tags are written, the field types in its `CustomData`. A secret it does not keep (a duplicate field, the
 * code's fields once read) is wiped.
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
        var otp: String? = null
        var legacySeed: String? = null
        var legacySettings: String? = null
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
                NOTES -> notes = text(field.value)
                OTP -> otp = text(field.value)
                TOTP_SEED -> legacySeed = text(field.value)
                TOTP_SETTINGS -> legacySettings = text(field.value)
                in TIME_OTP -> wipe(field.value)
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
            groupPath = raw.groupPath,
            groupId = raw.ancestorGroupUuids.lastOrNull() ?: "",
            tags = raw.tags.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() },
            customFields = customFields,
            fieldTypes = (raw.customData[EntryFieldTypes.CUSTOM_DATA_KEY] ?: raw.customData[EntryFieldTypes.FORMER_CUSTOM_DATA_KEY])
                ?.let(EntryFieldTypes::decode).orEmpty(),
            totp = TotpResolver.resolve(otp, legacySeed, legacySettings),
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
     * secret wiped: only the password stays a secret among them.
     */
    private fun text(value: FieldValue): String = when (value) {
        is FieldValue.Plain -> value.text
        is FieldValue.Protected -> value.secret.reveal().also { value.secret.wipe() }
    }

    private fun wipe(value: FieldValue) {
        (value as? FieldValue.Protected)?.secret?.wipe()
    }

    private companion object {
        const val TITLE = "Title"
        const val USERNAME = "UserName"
        const val PASSWORD = "Password"
        const val URL = "URL"
        const val NOTES = "Notes"
        const val OTP = "otp"
        const val TOTP_SEED = "TOTP Seed"
        const val TOTP_SETTINGS = "TOTP Settings"

        /** KeeTrayTOTP's fields: hidden from the custom fields, not read as a code (as on the Mac). */
        val TIME_OTP = setOf(
            "TimeOtp-Secret-Base32", "TimeOtp-Length", "TimeOtp-Period", "TimeOtp-Algorithm",
        )
    }
}
