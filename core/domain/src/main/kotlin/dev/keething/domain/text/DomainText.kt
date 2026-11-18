package dev.keething.domain.text

/** The texts core/domain makes (keething-spec plans T13b): each names its resource, `domain_<name in lower case>`. */
public enum class DomainText : TextKey {
    // An entry with no title and no web address
    UNTITLED,
    ;

    override val resource: String get() = "domain_" + name.lowercase()
}
