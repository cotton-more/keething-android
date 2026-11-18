package dev.keething.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import dev.keething.android.R
import dev.keething.card.CardText
import dev.keething.domain.text.DomainText
import dev.keething.domain.text.TextKey
import dev.keething.domain.text.UserText
import dev.keething.generator.GeneratorText
import dev.keething.totp.TotpText
import dev.keething.vault.VaultText

/**
 * A text the core made ([UserText], keething-spec plans T13b) as the app's own [UiText]: each core module's keys are
 * mapped to their resources below, by hand, so that R8 keeps them and the compiler sees every key
 * (CoreTextsTest checks that each maps to the resource its key names).
 */
internal fun UserText.ui(): UiText = when (this) {
    is UserText.Of -> UiText.Res(resourceOf(key), args.map(::argument))
    is UserText.Counted -> UiText.Plural(resourceOf(key), count, args.map(::argument))
    is UserText.Plain -> UiText.Plain(text)
    is UserText.Listed -> UiText.Listed(items.map { it.ui() })
    is UserText.Joined -> UiText.Joined(items.map { it.ui() }, separator)
}

/** The text in the language of the configuration shown. */
@Composable
@ReadOnlyComposable
internal fun UserText.resolve(): String = ui().resolve()

private fun argument(value: Any): Any = if (value is UserText) value.ui() else value

/** The string or plurals resource [key] names. */
internal fun resourceOf(key: TextKey): Int = when (key) {
    is CardText -> card(key)
    is TotpText -> totp(key)
    is DomainText -> domain(key)
    is GeneratorText -> generator(key)
    is VaultText -> vault(key)
    else -> error("no resources for ${key::class.simpleName}")
}

private fun card(key: CardText): Int = when (key) {
    CardText.TYPE_TEXT -> R.string.card_type_text
    CardText.TYPE_DATE -> R.string.card_type_date
    CardText.TYPE_UNTIL -> R.string.card_type_until
    CardText.TYPE_URL -> R.string.card_type_url
    CardText.TYPE_CODE -> R.string.card_type_code
    CardText.TYPE_PASSWORD -> R.string.card_type_password
    CardText.TYPE_CARD_NUMBER -> R.string.card_type_card_number
    CardText.TYPE_CARD_HOLDER -> R.string.card_type_card_holder
    CardText.TYPE_CARD_EXPIRY -> R.string.card_type_card_expiry
    CardText.TYPE_CARD_CVC -> R.string.card_type_card_cvc
    CardText.TYPE_CARD_PIN -> R.string.card_type_card_pin
    CardText.TYPE_CARD_TYPE -> R.string.card_type_card_type
    CardText.TYPE_WIFI_SSID -> R.string.card_type_wifi_ssid
    CardText.TYPE_WIFI_PASSWORD -> R.string.card_type_wifi_password
    CardText.TYPE_WIFI_SECURITY -> R.string.card_type_wifi_security
    CardText.TYPE_BANK_IBAN -> R.string.card_type_bank_iban
    CardText.TYPE_BANK_BIC -> R.string.card_type_bank_bic
    CardText.TYPE_BANK_NAME -> R.string.card_type_bank_name
    CardText.TYPE_BANK_HOLDER -> R.string.card_type_bank_holder
    CardText.TYPE_BANK_ACCOUNT -> R.string.card_type_bank_account
    CardText.TYPE_BANK_CODE -> R.string.card_type_bank_code
    CardText.TYPE_BANK_CORR -> R.string.card_type_bank_corr
    CardText.TYPE_DOC_TYPE -> R.string.card_type_doc_type
    CardText.TYPE_DOC_NUMBER -> R.string.card_type_doc_number
    CardText.TYPE_DOC_NAME -> R.string.card_type_doc_name
    CardText.TYPE_DOC_COUNTRY -> R.string.card_type_doc_country
    CardText.TYPE_DOC_BIRTH -> R.string.card_type_doc_birth
    CardText.TYPE_DOC_ISSUED -> R.string.card_type_doc_issued
    CardText.TYPE_DOC_EXPIRY -> R.string.card_type_doc_expiry
    CardText.TYPE_SSH_HOST -> R.string.card_type_ssh_host
    CardText.TYPE_SSH_PORT -> R.string.card_type_ssh_port
    CardText.TYPE_SSH_USER -> R.string.card_type_ssh_user
    CardText.TYPE_CRYPTO_SEED -> R.string.card_type_crypto_seed
    CardText.TYPE_CRYPTO_PASSPHRASE -> R.string.card_type_crypto_passphrase
    CardText.TYPE_CRYPTO_ADDRESS -> R.string.card_type_crypto_address
    CardText.TYPE_CRYPTO_XPUB -> R.string.card_type_crypto_xpub
    CardText.TYPE_CRYPTO_PATH -> R.string.card_type_crypto_path
    CardText.FULL_CARD_NUMBER -> R.string.card_full_card_number
    CardText.FULL_CARD_HOLDER -> R.string.card_full_card_holder
    CardText.FULL_CARD_EXPIRY -> R.string.card_full_card_expiry
    CardText.FULL_CARD_CVC -> R.string.card_full_card_cvc
    CardText.FULL_CARD_PIN -> R.string.card_full_card_pin
    CardText.FULL_CARD_TYPE -> R.string.card_full_card_type
    CardText.FULL_WIFI_SSID -> R.string.card_full_wifi_ssid
    CardText.FULL_WIFI_PASSWORD -> R.string.card_full_wifi_password
    CardText.FULL_WIFI_SECURITY -> R.string.card_full_wifi_security
    CardText.FULL_BANK_NAME -> R.string.card_full_bank_name
    CardText.FULL_BANK_CODE -> R.string.card_full_bank_code
    CardText.FULL_DOC_TYPE -> R.string.card_full_doc_type
    CardText.FULL_DOC_NUMBER -> R.string.card_full_doc_number
    CardText.FULL_DOC_NAME -> R.string.card_full_doc_name
    CardText.FULL_DOC_COUNTRY -> R.string.card_full_doc_country
    CardText.FULL_DOC_ISSUED -> R.string.card_full_doc_issued
    CardText.FULL_DOC_EXPIRY -> R.string.card_full_doc_expiry
    CardText.FULL_SSH_HOST -> R.string.card_full_ssh_host
    CardText.FULL_SSH_PORT -> R.string.card_full_ssh_port
    CardText.FULL_SSH_USER -> R.string.card_full_ssh_user
    CardText.FULL_CRYPTO_PASSPHRASE -> R.string.card_full_crypto_passphrase
    CardText.KIND_CARD -> R.string.card_kind_card
    CardText.KIND_WIFI -> R.string.card_kind_wifi
    CardText.KIND_BANK -> R.string.card_kind_bank
    CardText.KIND_DOCUMENT -> R.string.card_kind_document
    CardText.KIND_SSH -> R.string.card_kind_ssh
    CardText.KIND_CRYPTO -> R.string.card_kind_crypto
    CardText.EXPIRED_YESTERDAY -> R.string.card_expired_yesterday
    CardText.EXPIRED_TODAY -> R.string.card_expired_today
    CardText.EXPIRED_AGO -> R.string.card_expired_ago
    CardText.EXPIRES_TODAY -> R.string.card_expires_today
    CardText.EXPIRES_TOMORROW -> R.string.card_expires_tomorrow
    CardText.EXPIRES_IN -> R.string.card_expires_in
    CardText.DAYS -> R.plurals.card_days
    CardText.MONTHS -> R.plurals.card_months
    CardText.YEARS -> R.plurals.card_years
    CardText.ENTRY_EXPIRES -> R.string.card_entry_expires
    CardText.ROW_USERNAME -> R.string.card_row_username
    CardText.ROW_PASSWORD -> R.string.card_row_password
    CardText.ROW_URL -> R.string.card_row_url
    CardText.ROW_URL_NUMBERED -> R.string.card_row_url_numbered
    CardText.ROW_ONE_TIME_CODE -> R.string.card_row_one_time_code
    CardText.ROW_NOTE -> R.string.card_row_note
    CardText.ROW_NOTES -> R.string.card_row_notes
    CardText.ROW_VALUE -> R.string.card_row_value
    CardText.ROW_ADDRESS -> R.string.card_row_address
    CardText.NO_NAME -> R.string.card_no_name
    CardText.FACE_CARD -> R.string.card_face_card
    CardText.FACE_BANK_ACCOUNT -> R.string.card_face_bank_account
    CardText.FACE_DOCUMENT -> R.string.card_face_document
    CardText.FACE_UNTIL -> R.string.card_face_until
    CardText.FACE_CRYPTO_WALLET -> R.string.card_face_crypto_wallet
    CardText.FACE_RECOVERY_PHRASE -> R.string.card_face_recovery_phrase
    CardText.NOTE_PROTECTED -> R.string.card_note_protected
    CardText.WIFI_OPEN -> R.string.card_wifi_open
    CardText.BANK_IBAN -> R.string.card_bank_iban
    CardText.BANK_ACCOUNT -> R.string.card_bank_account
    CardText.BANK_BIC -> R.string.card_bank_bic
    CardText.BANK_BIK -> R.string.card_bank_bik
    CardText.BANK_CODE -> R.string.card_bank_code
    CardText.BANK_CORR -> R.string.card_bank_corr
    CardText.BANK_NAME -> R.string.card_bank_name
    CardText.BANK_IBAN_FAILS -> R.string.card_bank_iban_fails
    CardText.BANK_NOT_IBAN -> R.string.card_bank_not_iban
    CardText.BANK_ACCOUNT_BIK -> R.string.card_bank_account_bik
    CardText.BANK_CORR_BIK -> R.string.card_bank_corr_bik
    CardText.BANK_SHARE_CORR -> R.string.card_bank_share_corr
    CardText.DOCUMENT_HEADING -> R.string.card_document_heading
    CardText.SEED_BIP39 -> R.string.card_seed_bip39
    CardText.SEED_ELECTRUM -> R.string.card_seed_electrum
    CardText.SEED_UNKNOWN_WORD -> R.string.card_seed_unknown_word
    CardText.SEED_CHECKSUM_FAILS -> R.string.card_seed_checksum_fails
    CardText.SEED_WORD_COUNT -> R.string.card_seed_word_count
    CardText.SEED_NOT_A_PHRASE -> R.string.card_seed_not_a_phrase
    CardText.LANGUAGE_ENGLISH -> R.string.card_language_english
    CardText.LANGUAGE_JAPANESE -> R.string.card_language_japanese
    CardText.LANGUAGE_KOREAN -> R.string.card_language_korean
    CardText.LANGUAGE_SPANISH -> R.string.card_language_spanish
    CardText.LANGUAGE_CHINESE_SIMPLIFIED -> R.string.card_language_chinese_simplified
    CardText.LANGUAGE_CHINESE_TRADITIONAL -> R.string.card_language_chinese_traditional
    CardText.LANGUAGE_FRENCH -> R.string.card_language_french
    CardText.LANGUAGE_ITALIAN -> R.string.card_language_italian
    CardText.LANGUAGE_CZECH -> R.string.card_language_czech
    CardText.LANGUAGE_PORTUGUESE -> R.string.card_language_portuguese
    CardText.ADDRESS_SEGWIT -> R.string.card_address_segwit
    CardText.ADDRESS_NATIVE_SEGWIT -> R.string.card_address_native_segwit
    CardText.ADDRESS_NATIVE_SEGWIT_SCRIPT -> R.string.card_address_native_segwit_script
    CardText.ADDRESS_TAPROOT -> R.string.card_address_taproot
    CardText.ADDRESS_SEGWIT_VERSION -> R.string.card_address_segwit_version
    CardText.ADDRESS_LEGACY -> R.string.card_address_legacy
    CardText.ADDRESS_SCRIPT -> R.string.card_address_script
    CardText.ADDRESS_EVM -> R.string.card_address_evm
    CardText.ADDRESS_NO_CHECKSUM -> R.string.card_address_no_checksum
    CardText.KEY_BIP44_OR_ANY -> R.string.card_key_bip44_or_any
    CardText.NESTED_SEGWIT -> R.string.card_nested_segwit
    CardText.NATIVE_SEGWIT -> R.string.card_native_segwit
    CardText.NESTED_SEGWIT_MULTISIG -> R.string.card_nested_segwit_multisig
    CardText.NATIVE_SEGWIT_MULTISIG -> R.string.card_native_segwit_multisig
    CardText.MULTISIG -> R.string.card_multisig
    CardText.PATH_ACCOUNT -> R.string.card_path_account
    CardText.TITLE -> R.string.card_title
    CardText.TITLE_WILL_BE -> R.string.card_title_will_be
    CardText.TITLE_FIRST_LINE -> R.string.card_title_first_line
    CardText.FIELD_NO_NAME -> R.string.card_field_no_name
    CardText.CODE_NO_NAME -> R.string.card_code_no_name
    CardText.NAME_RESERVED -> R.string.card_name_reserved
    CardText.NAME_TAKEN -> R.string.card_name_taken
    CardText.MONTH_WARNING -> R.string.card_month_warning
    CardText.DATE_WARNING -> R.string.card_date_warning
    CardText.HOST_WARNING -> R.string.card_host_warning
    CardText.PORT_WARNING -> R.string.card_port_warning
    CardText.ADDRESS_FAILS -> R.string.card_address_fails
    CardText.KEY_PRIVATE -> R.string.card_key_private
    CardText.KEY_FAILS -> R.string.card_key_fails
    CardText.PATH_WARNING -> R.string.card_path_warning
    CardText.ATTACHMENT_NO_NAME -> R.string.card_attachment_no_name
    CardText.ATTACHMENT_TAKEN -> R.string.card_attachment_taken
    CardText.ATTACHMENTS_UNNAMED -> R.string.card_attachments_unnamed
    CardText.ATTACHMENTS_SAME -> R.string.card_attachments_same
    CardText.CODES_NONE -> R.string.card_codes_none
    CardText.CODES_TOO_MANY -> R.plurals.card_codes_too_many
    CardText.CODES_NO_LIST_NAME -> R.string.card_codes_no_list_name
    CardText.CODES_UNDERSCORE -> R.string.card_codes_underscore
    CardText.NO_QR_CODE -> R.string.card_no_qr_code
    CardText.CODE_EMPTY -> R.string.card_code_empty
    CardText.CODE_UNREADABLE -> R.string.card_code_unreadable
    CardText.CODE_TOO_SHORT -> R.string.card_code_too_short
    CardText.CODE_UNSUPPORTED -> R.string.card_code_unsupported
    CardText.CHANGE_PROTECTED -> R.string.card_change_protected
    CardText.CHANGE_NOT_PROTECTED -> R.string.card_change_not_protected
    CardText.CHANGE_KIND -> R.string.card_change_kind
    CardText.CHANGE_NO_TYPE -> R.string.card_change_no_type
    CardText.CHANGE_TYPE -> R.string.card_change_type
    CardText.CHANGE_FIELD_ORDER -> R.string.card_change_field_order
    CardText.CHANGE_TAGS -> R.string.card_change_tags
    CardText.CHANGE_EXPIRES -> R.string.card_change_expires
    CardText.CHANGE_YES -> R.string.card_change_yes
    CardText.CHANGE_ATTACHMENT -> R.string.card_change_attachment
    CardText.BYTES -> R.plurals.card_bytes
    CardText.SIZE_UNKNOWN -> R.string.card_size_unknown
    CardText.SPOKEN_HIDDEN -> R.string.card_spoken_hidden
    CardText.SPOKEN_HIDDEN_ENDING -> R.string.card_spoken_hidden_ending
    CardText.SPOKEN_SHOWN_HIDDEN -> R.string.card_spoken_shown_hidden
    CardText.SPOKEN_SHOWN_HIDDEN_ENDING -> R.string.card_spoken_shown_hidden_ending
}

private fun totp(key: TotpText): Int = when (key) {
    TotpText.UNSUPPORTED_NOT_TOTP -> R.string.totp_unsupported_not_totp
    TotpText.UNSUPPORTED_GOOGLE_EXPORT -> R.string.totp_unsupported_google_export
}

private fun domain(key: DomainText): Int = when (key) {
    DomainText.UNTITLED -> R.string.domain_untitled
}

private fun generator(key: GeneratorText): Int = when (key) {
    GeneratorText.REASON_EMPTY -> R.string.generator_reason_empty
    GeneratorText.REASON_COMMON_PASSWORD -> R.string.generator_reason_common_password
    GeneratorText.REASON_CONTEXT -> R.string.generator_reason_context
    GeneratorText.REASON_COMMON_WORD -> R.string.generator_reason_common_word
    GeneratorText.REASON_KEYBOARD_PATTERN -> R.string.generator_reason_keyboard_pattern
    GeneratorText.REASON_YEAR -> R.string.generator_reason_year
    GeneratorText.REASON_REPEATED -> R.string.generator_reason_repeated
    GeneratorText.REASON_SEQUENCE -> R.string.generator_reason_sequence
    GeneratorText.REASON_SHORTER_THAN -> R.plurals.generator_reason_shorter_than
    GeneratorText.REASON_DIGITS_ONLY -> R.string.generator_reason_digits_only
    GeneratorText.REASON_TOO_SHORT -> R.string.generator_reason_too_short
    GeneratorText.REASON_COULD_BE_LONGER -> R.string.generator_reason_could_be_longer
}

private fun vault(key: VaultText): Int = when (key) {
    VaultText.LOCATE_IN_LIST_ALREADY -> R.string.vault_locate_in_list_already
    VaultText.LOCATE_DATABASE_GONE -> R.string.vault_locate_database_gone
    VaultText.LOCATE_NOT_ON_STORAGE -> R.string.vault_locate_not_on_storage
    VaultText.LOCATE_FOLDER_LACKS_FILE -> R.string.vault_locate_folder_lacks_file
    VaultText.RUN_STEP -> R.string.vault_run_step
    VaultText.RUN_OPEN -> R.plurals.vault_run_open
    VaultText.RUN_SKIPPED -> R.string.vault_run_skipped
    VaultText.RUN_NOT_OPENED -> R.string.vault_run_not_opened
    VaultText.WHY_NOT_IN_LIST -> R.string.vault_why_not_in_list
    VaultText.WHY_CLOSED -> R.string.vault_why_closed
    VaultText.WHY_OPENING -> R.string.vault_why_opening
    VaultText.WHY_NOT_FOUND -> R.string.vault_why_not_found
    VaultText.WHY_KEY_FILE_NOT_THERE -> R.string.vault_why_key_file_not_there
    VaultText.WHY_KEY_FILE_UNUSABLE -> R.string.vault_why_key_file_unusable
    VaultText.WHY_CANNOT_BE_READ -> R.string.vault_why_cannot_be_read
    VaultText.FINGERPRINT_OFF_IN_SETTINGS -> R.string.vault_fingerprint_off_in_settings
    VaultText.FINGERPRINT_OFF_FOR_DATABASE -> R.string.vault_fingerprint_off_for_database
    VaultText.FINGERPRINT_NO_DATABASE -> R.string.vault_fingerprint_no_database
    VaultText.KEY_FILE_NOT_READ -> R.string.vault_key_file_not_read
    VaultText.KEY_FILE_TOO_LARGE -> R.string.vault_key_file_too_large
    VaultText.KEY_FILE_NO_ACCESS -> R.string.vault_key_file_no_access
    VaultText.KEY_FILE_EMPTY -> R.string.vault_key_file_empty
    VaultText.KEY_FILE_VERSION -> R.string.vault_key_file_version
    VaultText.KEY_FILE_MALFORMED -> R.string.vault_key_file_malformed
    VaultText.KEY_FILE_HASH_MISMATCH -> R.string.vault_key_file_hash_mismatch
}
