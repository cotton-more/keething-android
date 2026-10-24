package dev.keething.domain.sharing

/**
 * Where the sharing's requests are kept between runs (SPEC §10): one small file, sealed before it gets here. A
 * port, so the rules around it are tested without Android.
 */
public interface SharingStore {
    /** Null when there is none. */
    public fun read(): ByteArray?

    /** Replaces what was there. */
    public fun write(bytes: ByteArray)

    public fun delete()
}
