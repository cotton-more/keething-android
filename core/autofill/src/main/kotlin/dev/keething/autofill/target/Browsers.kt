package dev.keething.autofill.target

/**
 * The browsers whose web domain is taken as the page's (SPEC §7). Any other app can put any domain into its
 * view structure (a web view showing a bank's page, or claiming to), so outside these only the app's
 * package name counts.
 */
public object Browsers {
    public fun isBrowser(packageName: String): Boolean = packageName in PACKAGES

    private val PACKAGES = setOf(
        // Chrome and Chromium
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        // Firefox and its forks
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus", "org.mozilla.klar",
        "org.mozilla.fennec_fdroid", "org.ironfoxoss.ironfox", "io.github.forkmaintainers.iceraven", "us.spotco.fennec_dos",
        "net.waterfox.android.release", "org.torproject.torbrowser",
        // Others
        "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.vivaldi.browser",
        "com.duckduckgo.mobile.android", "com.opera.browser", "com.opera.mini.native", "com.kiwibrowser.browser",
        "app.vanadium.browser", "com.ecosia.android", "org.cromite.cromite", "com.yandex.browser",
    )
}
