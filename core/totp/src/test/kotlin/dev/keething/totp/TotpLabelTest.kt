package dev.keething.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TotpLabelTest {
    @Test
    fun aLinksIssuerIsItsParameterElseTheLabelBeforeTheColon() {
        assertEquals(TotpLabel("GitHub", "alex"), TotpParser.label("otpauth://totp/GitHub:alex?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"))
        assertEquals(TotpLabel("Dropbox", "alex@posteo.de"), TotpParser.label("otpauth://totp/Dropbox:alex%40posteo.de?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(TotpLabel("ACME Co", "john.doe@email.com"), TotpParser.label("otpauth://totp/ACME%20Co:john.doe@email.com?secret=X&issuer=ACME%20Co"))
        assertEquals(TotpLabel("Hetzner", "robot"), TotpParser.label("otpauth://totp/Old%3A%20robot?secret=X&issuer=Hetzner"), "the parameter wins; a colon may be encoded")
    }

    @Test
    fun aLinkWithoutAnIssuerOrAnAccountHasThemEmpty() {
        assertEquals(TotpLabel("", "alex"), TotpParser.label("otpauth://totp/alex?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(TotpLabel("", ""), TotpParser.label("otpauth://totp/?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(TotpLabel("Shop", ""), TotpParser.label("otpauth://totp/?secret=X&issuer=Shop"))
    }

    @Test
    fun anythingButATimeBasedLinkHasNoLabel() {
        assertNull(TotpParser.label("JBSWY3DPEHPK3PXP"))
        assertNull(TotpParser.label("otpauth://hotp/GitHub:alex?secret=X&counter=1"))
    }
}
