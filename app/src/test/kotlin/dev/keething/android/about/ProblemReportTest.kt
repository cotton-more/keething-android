package dev.keething.android.about

import dev.keething.android.ui.english
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Report a problem's new issue (SPEC §6.2 "About"). */
class ProblemReportTest {
    @Test
    fun theIssueSaysTheBuildAndThePhone() {
        val info = BuildInfo.parse("version=0.0.1\nversionCode=1\ncommit=becc85f\ndirty=true\n")
        val url = ProblemReport.url(ProblemReport.body(info, "16", 36, "Google Pixel 6").english())

        assertTrue(url.startsWith("https://github.com/cotton-more/keething-android/issues/new?body="))
        assertFalse(url.contains('+'), "spaces are %20, not +")
        val body = URLDecoder.decode(url.substringAfter("body="), Charsets.UTF_8)
        assertTrue(body.contains("**What happened**"))
        assertTrue(body.endsWith("---\nKeething 0.0.1 · build 1, commit becc85f with uncommitted changes\nAndroid 16 (API 36)\nGoogle Pixel 6"))
    }

    // spec: app.security-policy/AC5
    @Test
    fun aVulnerabilityIsSentToThePolicyFirst() {
        val body = URLDecoder.decode(ProblemReport.url(ProblemReport.body(BuildInfo.parse(""), "16", 36, "Google Pixel 6").english()).substringAfter("body="), Charsets.UTF_8)

        assertTrue(body.lines().first().contains("SECURITY.md"))
        assertTrue(body.indexOf("SECURITY.md") < body.indexOf("**What happened**"))
    }

    @Test
    fun theMakerIsSaidOnce() {
        assertEquals("Google Pixel 6", ProblemReport.device("Google", "Pixel 6"))
        assertEquals("Samsung SM-S911B", ProblemReport.device("samsung", "SM-S911B"))
        assertEquals("OnePlus CPH2449", ProblemReport.device("OnePlus", "CPH2449"))
        assertEquals("motorola edge 40", ProblemReport.device("motorola", "motorola edge 40"))
    }
}
