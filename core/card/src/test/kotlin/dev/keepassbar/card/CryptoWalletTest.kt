package dev.keepassbar.card

import dev.keepassbar.card.editing.EntryEditor
import dev.keepassbar.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Crypto wallets (SPEC §6.2, §6.3): the recovery phrase's check, the addresses' networks and checksums, the face, and the
 * offer to type such entries. Phrases from the BIP-39 vectors (trezor/python-mnemonic), Electrum seeds from Electrum's
 * tests, addresses from BIP-173 / BIP-350 and EIP-55.
 */
class CryptoWalletTest {
    // The recovery phrase

    private val vectors = listOf(
        Bip39Language.ENGLISH to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
        Bip39Language.ENGLISH to "void come effort suffer camp survey warrior heavy shoot primary clutch crush open amazing screen patrol group space point ten exist slush involve unfold",
        Bip39Language.CHINESE_SIMPLIFIED to "歇 歇 歇 歇 歇 歇 歇 歇 歇 歇 歇 逻",
        Bip39Language.CHINESE_SIMPLIFIED to "柄 需 固 姆 色 斥 霍 握 宾 琴 况 团 抵 经 摸 郭 沙 鸣 拖 妙 阳 辈 掉 迁",
        Bip39Language.CHINESE_TRADITIONAL to "歇 歇 歇 歇 歇 歇 歇 歇 歇 歇 歇 邏",
        Bip39Language.CHINESE_TRADITIONAL to "柄 需 固 姆 色 斥 霍 握 賓 琴 況 團 抵 經 摸 郭 沙 鳴 拖 妙 陽 輩 掉 遷",
        Bip39Language.CZECH to "zvyk zvyk zvyk zvyk zvyk zvyk zvyk zvyk zvyk zvyk zvyk zticha",
        Bip39Language.CZECH to "zavalit genetika kapusta tvrdost dopad ujmout zdobit mistr splav ptactvo fosfor hoch pocit beztak slon poplach mazivo tancovat pravda uvalit konkurs surovina nazvat vypadat",
        Bip39Language.FRENCH to "zoologie zoologie zoologie zoologie zoologie zoologie zoologie zoologie zoologie zoologie zoologie voter",
        Bip39Language.FRENCH to "vaillant chance dimanche sécable bonus séparer vecteur forcer raideur officier censurer cohésion meuble agiter prison mutation filière rincer novice solitude élargir renfort gronder tornade",
        Bip39Language.ITALIAN to "zuppa zuppa zuppa zuppa zuppa zuppa zuppa zuppa zuppa zuppa zuppa zerbino",
        Bip39Language.ITALIAN to "varcato codice enzima spessore brillante squillo vento insieme scoprire prugna circa cruciale peccato allusivo savio pilota inarcare simulato precluso sugo fegato sfamato lusso trono",
        Bip39Language.JAPANESE to "われる　われる　われる　われる　われる　われる　われる　われる　われる　われる　われる　ろんぶん",
        Bip39Language.JAPANESE to "よゆう　かんけい　けぶかい　へいこう　おかず　べんごし　りえき　じゆう　はんい　ともる　かほご　きぬごし　つみき　いきる　はかる　てふだ　しほう　ひろう　とくてん　ほったん　こさめ　ひつじゅひん　せつぞく　めんどう",
        Bip39Language.KOREAN to "힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 흑백",
        Bip39Language.KOREAN to "향상 담배 박수 추측 기술 충분히 협력 성적 줄무늬 인체 단위 딸아이 왼손 거짓 조깅 유명 석사 참석 이야기 크림 변동 진급 스케이트 하지만",
        Bip39Language.PORTUGUESE to "zumbido zumbido zumbido zumbido zumbido zumbido zumbido zumbido zumbido zumbido zumbido xeque",
        Bip39Language.PORTUGUESE to "vaidoso calota decote sambar batida seda vazio flora queda nuvem cadeado certeiro matinal afetivo praxe moinho feno resgatar nervoso sintonia dobrador recrutar gorro tonel",
        Bip39Language.SPANISH to "zurdo zurdo zurdo zurdo zurdo zurdo zurdo zurdo zurdo zurdo zurdo yodo",
        Bip39Language.SPANISH to "vampiro célula dos simio bono sondeo vencer haz remar papel castor codo nivel alarma rapaz ofensa gripe sagaz otro tabaco esfuerzo rojizo jinete traje",
    )

    @Test
    fun theVectorsOfEveryListPassInTheirLanguage() {
        for ((language, text) in vectors) {
            val phrase = assertNotNull(SeedPhrase.read(text), text)
            assertEquals(SeedPhrase.Check.Bip39(language), phrase.check, text)
            assertTrue(phrase.passes)
        }
    }

    @Test
    fun aTypoOrASwapIsCaughtAndSaidWhere() {
        val good = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        assertTrue(SeedPhrase.read(good)!!.passes)
        val typo = SeedPhrase.read(good.replace("worth", "worht"))!!
        assertEquals(SeedPhrase.Check.UnknownWord(6, Bip39Language.ENGLISH), typo.check)
        assertEquals("Word 7, “worht”, is not a BIP-39 word. A typo?", typo.summary())
        val swapped = SeedPhrase.read("legal winner year thank wave sausage worth useful legal winner thank yellow")!!
        assertEquals(SeedPhrase.Check.ChecksumFails(Bip39Language.ENGLISH), swapped.check)
        val short = SeedPhrase.read("legal winner thank year wave sausage worth useful legal winner thank")!!
        assertIs<SeedPhrase.Check.WordCount>(short.check)
        assertEquals(SeedPhrase.Check.NotAPhrase, SeedPhrase.read("Berlin Hamburg Muenchen Koeln Frankfurt Stuttgart")!!.check)
        assertNull(SeedPhrase.read("  1. 2. "))
    }

    @Test
    fun numbersCaseAndSpacesAsWrittenAreLeftOut() {
        val written = "1. Legal 2. winner\n3) thank, 4) year  5 wave 6 sausage 7 worth 8 useful 9 legal 10 winner 11 thank 12 yellow"
        val phrase = SeedPhrase.read(written)!!
        assertEquals(SeedPhrase.Check.Bip39(Bip39Language.ENGLISH), phrase.check)
        assertEquals("legal", phrase.words[0])
        assertEquals(12, phrase.words.size)
    }

    @Test
    fun spanishWordsTypedWithoutAccentsAreTheSameWords() {
        val phrase = SeedPhrase.read("vampiro celula dos simio bono sondeo vencer haz remar papel castor codo nivel alarma rapaz ofensa gripe sagaz otro tabaco esfuerzo rojizo jinete traje")!!
        assertEquals(SeedPhrase.Check.Bip39(Bip39Language.SPANISH), phrase.check)
        assertEquals("célula", phrase.words[1], "shown as the list spells it")
    }

    @Test
    fun electrumSeedsAreNamedNotFailed() {
        assertEquals(SeedPhrase.Check.Electrum("SegWit"), SeedPhrase.read("wild father tree among universe such mobile favorite target dynamic credit identify")!!.check)
        assertEquals(SeedPhrase.Check.Electrum("Standard"), SeedPhrase.read("cram swing cover prefer miss modify ritual silly deliver chunk behind inform able")!!.check)
        assertEquals(SeedPhrase.Check.Electrum("Standard"), SeedPhrase.read("OSTRICH SECURITY DEER AUNT CLIMB INNER ALPHA ARM MUTUAL MARBLE SOLID TASK")!!.check)
        assertEquals(SeedPhrase.Check.Electrum("SegWit"), SeedPhrase.read("眼 悲 叛 改 节 跃 衡 响 疆 股 遂 冬")!!.check)
        assertEquals(SeedPhrase.Check.Electrum("Standard"), SeedPhrase.read("almíbar tibio superar vencer hacha peatón príncipe matar consejo polen vehículo odisea")!!.check)
    }

    // Addresses

    @Test
    fun keccakAndEip55() {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", hex(Keccak.keccak256(ByteArray(0))))
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", hex(Keccak.keccak256("abc".toByteArray())))
        for (address in listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )) {
            val read = CryptoAddress.read(address)!!
            assertEquals(CryptoNetwork.ETHEREUM, read.network)
            assertEquals(AddressCheck.PASSES, read.check, address)
        }
        assertEquals(AddressCheck.FAILS, CryptoAddress.read("0x5aaeb6053F3E94C9b9A09f33669435E7Ef1BeAed")!!.check, "one letter's case changed")
        assertEquals(AddressCheck.NONE, CryptoAddress.read("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")!!.check, "one case: no checksum")
    }

    @Test
    fun bitcoinAddressesByTheirFormatAndChecksum() {
        fun read(text: String) = CryptoAddress.read(text)!!
        assertEquals(CryptoAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", CryptoNetwork.BITCOIN, "Legacy (1…)", AddressCheck.PASSES), read("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertEquals(AddressCheck.FAILS, read("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb").check)
        assertEquals("Script (3…)", read("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy").kind)
        assertEquals("Native SegWit (bc1q…)", read("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4").kind)
        assertEquals("Taproot (bc1p…)", read("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0").kind)
        val testnet = read("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7")
        assertEquals(CryptoNetwork.BITCOIN_TESTNET to AddressCheck.PASSES, testnet.network to testnet.check)
        // Bech32 where Bech32m belongs, and the reverse: BIP-350's invalid ones.
        assertEquals(AddressCheck.FAILS, read("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd").check)
        assertEquals(AddressCheck.FAILS, read("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh").check)
        assertEquals(AddressCheck.FAILS, read("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5").check, "a typo")
    }

    @Test
    fun otherNetworksAndWhatIsNoAddress() {
        val tron = CryptoAddress.read("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")!!
        assertEquals(CryptoNetwork.TRON to AddressCheck.PASSES, tron.network to tron.check)
        val solana = CryptoAddress.read("So11111111111111111111111111111111111111112")!!
        assertEquals(CryptoNetwork.SOLANA to AddressCheck.NONE, solana.network to solana.check)
        for (text in listOf("hello", "alex@example.com", "https://example.com", "DE89370400440532013000", "0x123", "bc1 qw508")) {
            assertNull(CryptoAddress.read(text), text)
        }
    }

    @Test
    fun extendedKeysAndPaths() {
        val zpub = ExtendedKey.read("zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs")!!
        assertTrue(zpub.passes)
        assertFalse(zpub.isPrivate)
        assertEquals("native SegWit", zpub.kind)
        val xprv = ExtendedKey.read("xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAF")!!
        assertTrue(xprv.passes && xprv.isPrivate)
        assertFalse(ExtendedKey.read("xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAG")!!.passes)
        assertEquals("BIP-84 · Bitcoin, native SegWit · account 1", DerivationPath.describe("m/84'/0'/0'"))
        assertEquals("BIP-44 · Ethereum · account 1", DerivationPath.describe("m/44h/60h/0h/0"))
        assertEquals(CryptoNetwork.ETHEREUM, DerivationPath.network("m/44'/60'/0'/0/0"))
        assertNull(DerivationPath.describe("m/84'/x"))
        assertNull(DerivationPath.describe("44/0/0"))
    }

    // The face

    private val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val wallet = Entry(
        "w", title = "Cold wallet",
        customFields = listOf(
            field("Seed", phrase, protected = true), field("Passphrase", "harbour-lamp-1987", protected = true),
            field("BTC address", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"), field("Second address", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
            field("zpub", "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"),
            field("Path", "m/84'/0'/0'"), field("PIN", "1234"),
        ),
    )
    private val walletTypes = "Seed = crypto.seed\nPassphrase = crypto.passphrase\nBTC address = crypto.address\nSecond address = crypto.address\n" +
        "zpub = crypto.xpub\nPath = crypto.path"

    @Test
    fun theWalletsSecretsAreMaskedAndNeverCopied() {
        val card = EntryCardBuilder.build(typed(wallet, walletTypes), now, de)
        val crypto = assertNotNull(card.crypto)
        assertEquals(CryptoNetwork.BITCOIN, crypto.network)
        assertEquals(listOf("BTC address", "Second address"), crypto.addresses.map { it.label })
        assertEquals("Legacy (1…)", crypto.addresses[1].read?.kind)
        assertEquals("BIP-84 · Bitcoin, native SegWit · account 1", crypto.path?.description)
        val rows = card.rows.associateBy { it.label }
        for (secret in listOf("Seed", "Passphrase")) {
            assertTrue(rows.getValue(secret).isConcealed, secret)
            assertFalse(rows.getValue(secret).isCopyable, "$secret is never copied")
        }
        assertTrue(rows.getValue("BTC address").isCopyable)
        assertTrue(rows.getValue("zpub").isCopyable)
        assertTrue(rows.getValue("PIN").isCopyable, "not a part of the wallet: a field as any")
        assertEquals(setOf("custom-0", "custom-1", "custom-2", "custom-3", "custom-4", "custom-5"), crypto.faceRowIds)
        assertEquals(EntryFace.Crypto(CryptoNetwork.BITCOIN, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", hasPhrase = true), EntryFace.of(typed(wallet, walletTypes)))
        assertEquals("Bitcoin · bc1qar…5mdq", EntryFace.of(typed(wallet, walletTypes))!!.subtitle)
    }

    @Test
    fun aPrivateKeyTypedAsTheExtendedKeyIsTreatedAsThePhrase() {
        val entry = Entry("k", customFields = listOf(field("Key", "xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAF")))
        val card = EntryCardBuilder.build(typed(entry, "Key = crypto.xpub"), now, de)
        val crypto = assertNotNull(card.crypto)
        assertNull(crypto.xpub)
        assertEquals("custom-0", crypto.privateKeyRowId)
        val row = card.rows.single()
        assertTrue(row.isConcealed && !row.isCopyable)
    }

    @Test
    fun aWatchOnlyWalletOrAPhraseAloneMakesAFaceToo() {
        val watch = typed(Entry("o", customFields = listOf(field("ETH", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))), "ETH = crypto.address")
        assertEquals("Ethereum · 0x5aAe…eAed", EntryFace.of(watch)!!.subtitle)
        val seedOnly = typed(Entry("s", customFields = listOf(field("Words", phrase, protected = true))), "Words = crypto.seed")
        assertEquals("Crypto wallet · recovery phrase", EntryFace.of(seedOnly)!!.subtitle)
        assertNotNull(EntryCardBuilder.build(seedOnly, now, de).crypto)
    }

    // The offer

    @Test
    fun theOfferTypesAPhraseAnAddressAndTheRest() {
        val suggestion = assertNotNull(TypeSuggestion.of(wallet))
        assertEquals(TypeSuggestion.Kind.CRYPTO, suggestion.kind)
        assertEquals(
            mapOf(
                "Seed" to FieldType.CRYPTO_SEED, "Passphrase" to FieldType.CRYPTO_PASSPHRASE, "BTC address" to FieldType.CRYPTO_ADDRESS,
                "Second address" to FieldType.CRYPTO_ADDRESS, "zpub" to FieldType.CRYPTO_XPUB, "Path" to FieldType.CRYPTO_PATH,
            ),
            suggestion.types,
        )
        // An unprotected phrase by its checksum, whatever its name.
        val plain = Entry("p", customFields = listOf(field("Words", phrase)))
        assertEquals(mapOf("Words" to FieldType.CRYPTO_SEED), TypeSuggestion.of(plain)?.types)
        assertNull(TypeSuggestion.of(typed(wallet, walletTypes)), "typed already")
    }

    @Test
    fun theOfferIsNotMadeForOrdinaryFields() {
        val login = Entry(
            "l", customFields = listOf(
                field("Passphrase", "correct horse battery staple", protected = true), field("Notes", "the quick brown fox"),
                field("Solana-ish", "So11111111111111111111111111111111111111112"),
            ),
        )
        assertNull(TypeSuggestion.of(login), "a passphrase without a phrase, an address without a checksum or an address's name")
        val typo = Entry("t", customFields = listOf(field("Words", phrase.replace("worth", "worht"))))
        assertNull(TypeSuggestion.of(typo), "words alone under no phrase's name must pass their check")
        val named = Entry("n", customFields = listOf(field("Recovery phrase", phrase.replace("worth", "worht"))))
        assertEquals(mapOf("Recovery phrase" to FieldType.CRYPTO_SEED), TypeSuggestion.of(named)?.types, "named so: its typo is shown on the face")
    }

    @Test
    fun theHistoryMasksAPhraseThatIsNotProtected() {
        val old = Entry("h", customFields = listOf(field("Seed", phrase)), fieldTypes = mapOf("Seed" to "crypto.seed"))
        val new = old.copy(customFields = listOf(field("Seed", phrase.replace("yellow", "zoo"))))
        assertTrue(dev.keepassbar.card.history.EntryVersionDiff.changes(old, new).single { it.field == "Seed" }.isProtected)
    }

    // The editor

    @Test
    fun theEditorSaysWhatFailsItsCheck() {
        val entry = Entry(
            "e", customFields = listOf(
                field("Seed", phrase.replace("worth", "worht"), protected = true), field("Good seed", phrase),
                field("Address", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"), field("Monero", "44AFFq5kSiGBoZ4NMDwYtN18obc8AemS33DBLWs3H7otXft3XjrpDtQGv7SqSsaBYBb98uNbr2VBBEt7f2wfn3RVGQBEP3A"),
                field("Key", "xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAF"),
                field("Path", "84'/0'/0'"),
            ),
            fieldTypes = mapOf(
                "Seed" to "crypto.seed", "Good seed" to "crypto.seed", "Address" to "crypto.address", "Monero" to "crypto.address",
                "Key" to "crypto.xpub", "Path" to "crypto.path",
            ),
        )
        val editor = EntryEditor.edit(entry, now)
        assertEquals(
            listOf(
                "Word 7, “worht”, is not a BIP-39 word. A typo?", null, "The address fails its checksum. A typo?", null,
                "A private key (xprv): whoever has it has the wallet. It is shown, never copied.", "Not a derivation path: write it as m/84'/0'/0'.",
            ),
            editor.start.fields.map { editor.fieldWarning(it) },
        )
    }
}
