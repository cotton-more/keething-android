package dev.keething.autofill.target

import dev.keething.search.site.SiteAddress

/** Where the values go: a web page in a browser, or an app. */
public sealed interface FillTarget {
    /**
     * A page in a known browser; only the browser's word for the page's domain is trusted, never an app's.
     * [portKnown]: the browser named the port; Android's web domain is usually the host alone.
     */
    public data class WebPage(val site: SiteAddress, val browserPackage: String, val portKnown: Boolean = true) : FillTarget

    /** An app, by its package name (the system's, not the app's own claim). */
    public data class App(val packageName: String) : FillTarget
}
