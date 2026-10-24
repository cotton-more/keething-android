package dev.keething.search

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import java.text.Collator
import java.util.Locale
import java.util.UUID

internal fun entry(
    title: String,
    username: String = "",
    url: String = "",
    notes: String = "",
    group: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    custom: List<CustomField> = emptyList(),
    password: String = "",
    attachments: List<String> = emptyList(),
) = Entry(
    id = UUID.randomUUID().toString(),
    title = title,
    username = username,
    password = Secret.of(password),
    url = url,
    notes = notes,
    groupPath = group,
    tags = tags,
    customFields = custom,
    attachments = attachments.map { Attachment(it, 1) },
)

internal val englishCollator: Collator = Collator.getInstance(Locale.ENGLISH)

internal fun search(entries: List<Entry>, query: String): List<String> =
    EntrySearch.filter(EntrySearch.index(entries, englishCollator), query).map { it.title }
