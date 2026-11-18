package dev.keething.android.ui

import dev.keething.android.R
import kotlin.test.Test
import kotlin.test.assertEquals

class UiTextTest {
    @Test
    fun aNoticeReadsAsTheUserSeesIt() {
        assertEquals("Password copied · clears in 30 s", Notice.copied("Password").text.english())
        assertEquals("Next code copied · works in 1 s", Notice.nextCodeCopied(1).text.english())
        assertEquals("Undo", Notice(uiPlain("x")).actionLabel.english())
    }

    @Test
    fun aListIsJoinedInTheLanguage() {
        assertEquals("", uiListed(emptyList()).english())
        assertEquals("A", uiListed(listOf(uiPlain("A"))).english())
        assertEquals("A and B", uiListed(listOf(uiPlain("A"), uiPlain("B"))).english())
        assertEquals("A, B and C", uiListed(listOf("A", "B", "C").map(::uiPlain)).english())
        assertEquals("Password copied · clears in 30 s", uiText(R.string.notice_copied, uiPlain("Password")).english())
    }
}
