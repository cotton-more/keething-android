package dev.keepassbar.domain.vault

/** An attachment's name and size; the content stays with the open database. */
public data class Attachment(
    val name: String,
    /** In bytes; null when the file does not say (a reference to no attachment). */
    val size: Int?,
)
