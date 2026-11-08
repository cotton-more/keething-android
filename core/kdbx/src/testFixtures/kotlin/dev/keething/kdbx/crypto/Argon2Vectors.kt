package dev.keething.kdbx.crypto

/**
 * Argon2's reference values from the Mac tests (`KDBXTests.swift`): OpenSSL 3+ (`openssl kdf ARGON2D/ARGON2ID`),
 * password "password", salt "somesaltsomesalt", 32 bytes. Shared by the Kotlin Argon2's tests and the native one's.
 */
public object Argon2Vectors {
    public val password: ByteArray get() = "password".encodeToByteArray()
    public val salt: ByteArray get() = "somesaltsomesalt".encodeToByteArray()

    public data class Case(val parameters: Argon2.Parameters, val expected: String)

    private fun case(variant: Argon2.Variant, iterations: Long, memoryKiB: Int, parallelism: Int, expected: String) =
        Case(Argon2.Parameters(variant, iterations, memoryKiB, parallelism), expected)

    public val cases: List<Case> = listOf(
        case(Argon2.Variant.D, 1, 8, 1, "8f6b35da93486025c6a1c6361da620487040700d56c30b87eb870865781d3274"),
        case(Argon2.Variant.D, 2, 32, 1, "9b60e17f88d110784a65c348586c14f55af9d33897ca3210c24545b669a6eb42"),
        case(Argon2.Variant.D, 1, 32, 2, "f32d2c124360ffef573f916b33885fb0f17b4721e03fbd959ef5e14e86a96e4f"),
        case(Argon2.Variant.D, 1, 64, 4, "503b34e3218d3d936abe106b946720232dd3783877b17ca793c4c71a647562bb"),
        case(Argon2.Variant.D, 1, 32, 1, "ffb666505060953d67c04a0351261634eb042e7cf13d4009957d616333405c1a"),
        case(Argon2.Variant.D, 3, 1024, 2, "70bbe1f806bdedfeb0986154bbd155569c2bdef982d2c08d5263cc0e924b0188"),
        case(Argon2.Variant.D, 2, 64, 4, "21477a8712db011b1bda81a1d18d4ccf6f1100729d89d9215c28d1fcd3862dd4"),
        case(Argon2.Variant.ID, 1, 8, 1, "05b6ca8f8038b683887ad4916533858b49cb52040126c134f4c6e9d8711b8c09"),
        case(Argon2.Variant.ID, 1, 32, 1, "fa1e90316994a42525e8e3db47cc59ed150c98d47db299b2bcb689d8217af811"),
        case(Argon2.Variant.ID, 3, 1024, 2, "a331c9e908e5cc6a7fbf63e915ca3ae2d797e03965116715a95b4efec189a160"),
        case(Argon2.Variant.ID, 2, 64, 4, "4111a92266f8015c4224e634fdc7352dad7e17abe362d2386331333226f8189e"),
    )
}
