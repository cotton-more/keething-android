package dev.keething.autofill.target

/**
 * The browsers whose web domain is taken as the page's (SPEC §7). Any other app can put any domain into its
 * view structure (a web view showing a bank's page, or claiming to), so outside these only the app's
 * package name counts. A browser is known by its package and the SHA-256 of a certificate it is signed with:
 * an app installed under the package of a browser the phone does not have is signed otherwise, and stays an app.
 * How to add one: DEVELOPMENT.md, "Autofill's browsers".
 */
public object Browsers {
    /** [packageName] is a listed browser and [signing] says it is signed with one of its certificates. */
    public fun isBrowser(packageName: String, signing: SigningCheck): Boolean =
        CERTIFICATES[packageName]?.any { signing.isSignedBy(packageName, it) } == true

    private fun sha256(hex: String): ByteArray = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Each package with the SHA-256 of its signing certificates (an older certificate of a rotated key is accepted
    // by the system as well).
    private val CERTIFICATES: Map<String, List<ByteArray>> = mapOf(
        // From Google's list of browsers privileged for passkeys, each package's cert_fingerprint_sha256
        // (https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json, read 2026-11-13).
        // Chrome and Chromium
        "com.android.chrome" to listOf("f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83", "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00"),
        "com.chrome.beta" to listOf("da633d34b69e63ae2103b49d53ce052fc5f7f3c53aab94fdc2a208bdfd14249c", "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"),
        "com.chrome.dev" to listOf("9044ee5fee4bbc5e21dd44665431c4eb1f1f71a32716a0bc927bcbb39233cabf", "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"),
        "com.chrome.canary" to listOf("2019dfa1fb23efbf70c5bcd1443c5beab04f3f2ff4366e9ac1e3457639a24cfc"),
        "org.chromium.chrome" to listOf("c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3", "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00"),
        // Firefox and its forks
        "org.mozilla.firefox" to listOf("a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04"),
        "org.mozilla.firefox_beta" to listOf("a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04"),
        "org.mozilla.fenix" to listOf("5004779088e7f988d5bc5cc5f8798febf4f8cd084a1b2a46efd4c8ee4aeaf211"),
        "org.mozilla.focus" to listOf("6203a473be36d64ee37f87fa500edbc79eab930610ab9b9fa4ca7d5c1f1b4ffc"),
        "org.mozilla.klar" to listOf("6203a473be36d64ee37f87fa500edbc79eab930610ab9b9fa4ca7d5c1f1b4ffc"),
        "net.waterfox.android.release" to listOf("2939997a2d8f07303ceb37ad6810afef0bda710be2116476e3525a7379ec2e1a"),
        // Others
        "com.brave.browser" to listOf("9c2db70513515fdbfbbc585b3edf3d7123d4dc67c94ffd306361c1d79bbf18ac"),
        "com.microsoft.emmx" to listOf("01e1999710a82c2749b4d50c445dc85d670b6136089d0a766a73827c82a1eac9"),
        "com.sec.android.app.sbrowser" to listOf("34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42", "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"),
        "com.vivaldi.browser" to listOf("e8a78544655ba8c09817f732768f5689b1662ec4b2bc5a0bc0ec138d33ca3d1e"),
        "com.duckduckgo.mobile.android" to listOf("bb7bb31c573c46a1da7fc5c528a6acf432108456feec50810c7f33694eb3d2d4"),
        "com.opera.browser" to listOf("5d6afbf87f652af04647ada0df634cf22370900b164b09d50bd23aa2cb5285b8"),
        "com.opera.mini.native" to listOf("57acbc525f1b2ebd19196cd6f014397cc910fd18841e0ae850febc3e1e593ff2"),
        "app.vanadium.browser" to listOf("c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"),
        "com.yandex.browser" to listOf("aca405ded8b25cb2e8c6da69425d2b4307d087c1276fc06ad5942731ccc51dba"),
        // From the signer of each one's release, read 2026-11-13: an F-Droid repository's index (index-v2.json,
        // the version's signer) or the APK of its latest GitHub release (its v2/v3 signing block).
        // Fennec F-Droid, built and signed by F-Droid (f-droid.org/repo).
        "org.mozilla.fennec_fdroid" to listOf("06665358efd8ba05be236a47a12cb0958d7d75dd939d77c2b31f5398537ebdc5"),
        // IronFox, its own repository (fdroid.ironfoxoss.org/fdroid/repo).
        "org.ironfoxoss.ironfox" to listOf("c5e291b5a571f9c8cd9a9799c2c94e02ec9703948893f2ca756d67b94204f904"),
        // Iceraven, GitHub fork-maintainers/iceraven-browser 2.52.0.
        "io.github.forkmaintainers.iceraven" to listOf("9c0d22379f487b70a4f9f8bec0173cf91a1644f08f93385b5b782ce37660ba81"),
        // Tor Browser, the Guardian Project's repository (guardianproject.info/fdroid/repo).
        "org.torproject.torbrowser" to listOf("20061f045e737c67375c17794cfedb436a03cec6bacb7cb9f96642205ca2cec8"),
        // Kiwi, GitHub kiwibrowser/src.next 14310011181 (the project's last release).
        "com.kiwibrowser.browser" to listOf("829b930e919cd56c9a67617c312e3b425a38894b929e735c3d391d9c51b9e4c0"),
        // Cromite, GitHub uazo/cromite v153.0.8010.37.
        "org.cromite.cromite" to listOf("633fa41d8211d6d0916a819b89668c6de92e64232da67f9d16fd81c3b7e923ff"),
    ).mapValues { (_, hexes) -> hexes.map(::sha256) }
}

/** What the system says of an installed app's signature: [packageName] is signed with the certificate whose SHA-256 is [sha256]. */
public fun interface SigningCheck {
    public fun isSignedBy(packageName: String, sha256: ByteArray): Boolean
}
