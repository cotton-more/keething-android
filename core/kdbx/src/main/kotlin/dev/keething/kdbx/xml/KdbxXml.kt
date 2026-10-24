package dev.keething.kdbx.xml

/** KeePass XML outside a database file. */
public object KdbxXml {
    /**
     * The XML of `keepassxc-cli export -f xml`, with the protected values in the clear (marked
     * `ProtectInMemory="True"`). Throws `KdbxException.Corrupt` for anything else.
     */
    public fun readExport(xml: ByteArray): KdbxDocument = KdbxXmlReader.read(xml)
}
