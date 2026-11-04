package dev.keething.kdbx.crypto

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Argon2d and Argon2id, version 0x13 (RFC 9106): the key derivation of KDBX 4. Port of the Mac `Argon2`.
 * The lanes of a slice are filled in parallel, as the specification allows, so the result is the same as a
 * sequential run. Public for the sharing's pictures (SPEC §10), which stretch a request's digest with it.
 */
public object Argon2 {
    public enum class Variant(internal val id: Int) { D(0), ID(2) }

    public data class Parameters(
        val variant: Variant,
        /** 1 to 2³²−1. */
        val iterations: Long,
        /** In KiB (1 KiB blocks). */
        val memoryKiB: Int,
        val parallelism: Int,
        val version: Int = 0x13,
    )

    private const val WORDS = 128
    private const val SYNC_POINTS = 4

    /** Blocks filled between two checkpoints: about a millisecond's work. */
    private const val CHECKPOINT_BLOCKS = 1024

    /**
     * [checkpoint] is called between steps, a few thousand times a second at most: what it throws stops the hash
     * (keething-spec kdbx.hardening AC6, a derivation given up), the memory wiped.
     */
    public fun hash(
        password: ByteArray,
        salt: ByteArray,
        parameters: Parameters,
        length: Int = 32,
        checkpoint: () -> Unit = {},
    ): ByteArray {
        val p = parameters
        require(p.version == 0x13 && p.parallelism in 1..0xFF_FFFF && p.iterations in 1..0xFFFF_FFFFL && length >= 4)
        require(p.memoryKiB.toLong() >= 8L * p.parallelism)

        val h = Blake2b(64)
        // Each a 32-bit little-endian number: the iterations' low 32 bits are all of them.
        for (value in intArrayOf(p.parallelism, length, p.memoryKiB, p.iterations.toInt(), p.version, p.variant.id)) {
            h.update(value)
        }
        h.update(password.size); h.update(password)
        h.update(salt.size); h.update(salt)
        h.update(0) // no secret
        h.update(0) // no associated data
        val h0 = h.finish()

        val lanes = p.parallelism
        val segmentLength = p.memoryKiB / (lanes * SYNC_POINTS)
        val laneLength = segmentLength * SYNC_POINTS
        val memory = Array(lanes) { LongArray(laneLength * WORDS) }
        val pool = if (lanes > 1) Workers.start(lanes) else null
        try {
            // The first two blocks of every lane.
            for (lane in 0 until lanes) {
                for (i in 0 until 2) {
                    val block = variableHash(h0 + Bytes.le32(i) + Bytes.le32(lane), 1024)
                    for (w in 0 until WORDS) memory[lane][i * WORDS + w] = Bytes.longLe(block, w * 8)
                    block.fill(0)
                }
            }
            val filler = Filler(memory, p, segmentLength, laneLength, checkpoint)
            for (pass in 0L until p.iterations) {
                for (slice in 0 until SYNC_POINTS) {
                    if (pool == null) {
                        filler.fillSegment(pass, slice, 0)
                    } else {
                        try {
                            pool.invokeAll((0 until lanes).map { lane -> Callable { filler.fillSegment(pass, slice, lane) } })
                                .forEach { it.get() }
                        } catch (failure: ExecutionException) {
                            // What a lane's checkpoint threw, as from a single lane.
                            throw failure.cause ?: failure
                        }
                    }
                }
            }

            // The last blocks of all lanes, XORed.
            val final = LongArray(WORDS)
            for (lane in 0 until lanes) {
                val base = (laneLength - 1) * WORDS
                for (w in 0 until WORDS) final[w] = final[w] xor memory[lane][base + w]
            }
            val finalBytes = ByteArray(1024)
            for (w in 0 until WORDS) Bytes.putLongLe(finalBytes, w * 8, final[w])
            final.fill(0)
            return variableHash(finalBytes, length).also { finalBytes.fill(0) }
        } finally {
            pool?.shutdownNow()
            memory.forEach { it.fill(0) }
            h0.fill(0)
        }
    }

    /** H' of the specification: BLAKE2b extended to any length. */
    internal fun variableHash(input: ByteArray, length: Int): ByteArray {
        val first = Blake2b(minOf(length, 64))
        first.update(length)
        first.update(input)
        if (length <= 64) return first.finish()
        val out = ByteArray(length)
        var v = first.finish()
        val r = (length + 31) / 32 - 2
        v.copyInto(out, 0, 0, 32)
        for (i in 1 until r) {
            val next = Blake2b.hash(v)
            v.fill(0)
            v = next
            v.copyInto(out, i * 32, 0, 32)
        }
        val last = Blake2b.hash(v, length - 32 * r)
        last.copyInto(out, 32 * r)
        v.fill(0)
        last.fill(0)
        return out
    }

    private object Workers {
        fun start(lanes: Int): ExecutorService {
            val threads = minOf(lanes, Runtime.getRuntime().availableProcessors())
            return Executors.newFixedThreadPool(threads) { task -> Thread(task, "argon2").apply { isDaemon = true } }
        }
    }

    /**
     * Fills the memory. The lane workers write disjoint blocks and read only blocks finished in an earlier
     * slice (or by themselves); `invokeAll` between slices orders the writes before the reads.
     */
    private class Filler(
        private val memory: Array<LongArray>,
        private val p: Parameters,
        private val segmentLength: Int,
        private val laneLength: Int,
        private val checkpoint: () -> Unit,
    ) {
        private val lanes = p.parallelism

        fun fillSegment(pass: Long, slice: Int, lane: Int) {
            val dataIndependent = p.variant == Variant.ID && pass == 0L && slice < SYNC_POINTS / 2
            // The address block, its input and a zero block (data-independent addressing), and the two working
            // blocks of the compression.
            val address = LongArray(WORDS)
            val input = LongArray(WORDS)
            val zero = LongArray(WORDS)
            val r = LongArray(WORDS)
            val t = LongArray(WORDS)
            try {
                if (dataIndependent) {
                    input[0] = pass; input[1] = lane.toLong(); input[2] = slice.toLong()
                    input[3] = (laneLength.toLong() * lanes); input[4] = p.iterations
                    input[5] = p.variant.id.toLong()
                }
                fun nextAddresses() {
                    input[6]++
                    compress(zero, 0, input, 0, address, 0, false, r, t)
                    compress(zero, 0, address, 0, address, 0, false, r, t)
                }

                var start = 0
                if (pass == 0L && slice == 0) {
                    start = 2
                    if (dataIndependent) nextAddresses()
                }
                val laneMemory = memory[lane]
                var current = slice * segmentLength + start
                var previous = if (current == 0) laneLength - 1 else current - 1

                for (index in start until segmentLength) {
                    if (index % CHECKPOINT_BLOCKS == 0) checkpoint()
                    val pseudoRandom = if (dataIndependent) {
                        if (index % WORDS == 0) nextAddresses()
                        address[index % WORDS]
                    } else {
                        laneMemory[previous * WORDS]
                    }
                    val refLane = if (pass == 0L && slice == 0) lane else ((pseudoRandom ushr 32) % lanes).toInt()
                    val refIndex = indexAlpha(pass, slice, index, pseudoRandom and 0xFFFF_FFFFL, refLane == lane)
                    compress(
                        laneMemory, previous * WORDS, memory[refLane], refIndex * WORDS,
                        laneMemory, current * WORDS, pass > 0, r, t,
                    )
                    previous = current
                    current++
                }
            } finally {
                address.fill(0); input.fill(0); r.fill(0); t.fill(0)
            }
        }

        private fun indexAlpha(pass: Long, slice: Int, index: Int, pseudoRand: Long, sameLane: Boolean): Int {
            val area = if (pass == 0L) {
                when {
                    slice == 0 -> index - 1
                    sameLane -> slice * segmentLength + index - 1
                    else -> slice * segmentLength + if (index == 0) -1 else 0
                }
            } else {
                if (sameLane) laneLength - segmentLength + index - 1
                else laneLength - segmentLength + if (index == 0) -1 else 0
            }
            val rel = (pseudoRand * pseudoRand) ushr 32
            val relative = area - 1 - ((area.toLong() * rel) ushr 32)
            val startPosition = if (pass == 0L || slice == SYNC_POINTS - 1) 0 else (slice + 1) * segmentLength
            return ((startPosition + relative) % laneLength).toInt()
        }
    }

    /** The compression G: out = P(prev ^ ref) ^ (prev ^ ref) [^ the old out, in passes after the first]. */
    private fun compress(
        prev: LongArray, prevOffset: Int,
        ref: LongArray, refOffset: Int,
        out: LongArray, outOffset: Int,
        xor: Boolean, r: LongArray, t: LongArray,
    ) {
        for (i in 0 until WORDS) r[i] = prev[prevOffset + i] xor ref[refOffset + i]
        if (xor) {
            for (i in 0 until WORDS) t[i] = r[i] xor out[outOffset + i]
        } else {
            r.copyInto(t)
        }
        for (i in 0 until 8) {
            val b = 16 * i
            round(r, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
        }
        for (i in 0 until 8) {
            val b = 2 * i
            round(r, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
        }
        for (i in 0 until WORDS) out[outOffset + i] = t[i] xor r[i]
    }

    /** BLAKE2b's round with the multiplication of BlaMka, on 16 words loaded into locals. */
    @Suppress("LongParameterList")
    private fun round(
        v: LongArray, i0: Int, i1: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int, i12: Int, i13: Int, i14: Int, i15: Int,
    ) {
        var v0 = v[i0]; var v1 = v[i1]; var v2 = v[i2]; var v3 = v[i3]
        var v4 = v[i4]; var v5 = v[i5]; var v6 = v[i6]; var v7 = v[i7]
        var v8 = v[i8]; var v9 = v[i9]; var v10 = v[i10]; var v11 = v[i11]
        var v12 = v[i12]; var v13 = v[i13]; var v14 = v[i14]; var v15 = v[i15]

        // g(v0, v4, v8, v12)
        v0 = blaMka(v0, v4); v12 = (v12 xor v0).rotateRight(32); v8 = blaMka(v8, v12); v4 = (v4 xor v8).rotateRight(24)
        v0 = blaMka(v0, v4); v12 = (v12 xor v0).rotateRight(16); v8 = blaMka(v8, v12); v4 = (v4 xor v8).rotateRight(63)
        // g(v1, v5, v9, v13)
        v1 = blaMka(v1, v5); v13 = (v13 xor v1).rotateRight(32); v9 = blaMka(v9, v13); v5 = (v5 xor v9).rotateRight(24)
        v1 = blaMka(v1, v5); v13 = (v13 xor v1).rotateRight(16); v9 = blaMka(v9, v13); v5 = (v5 xor v9).rotateRight(63)
        // g(v2, v6, v10, v14)
        v2 = blaMka(v2, v6); v14 = (v14 xor v2).rotateRight(32); v10 = blaMka(v10, v14); v6 = (v6 xor v10).rotateRight(24)
        v2 = blaMka(v2, v6); v14 = (v14 xor v2).rotateRight(16); v10 = blaMka(v10, v14); v6 = (v6 xor v10).rotateRight(63)
        // g(v3, v7, v11, v15)
        v3 = blaMka(v3, v7); v15 = (v15 xor v3).rotateRight(32); v11 = blaMka(v11, v15); v7 = (v7 xor v11).rotateRight(24)
        v3 = blaMka(v3, v7); v15 = (v15 xor v3).rotateRight(16); v11 = blaMka(v11, v15); v7 = (v7 xor v11).rotateRight(63)
        // g(v0, v5, v10, v15)
        v0 = blaMka(v0, v5); v15 = (v15 xor v0).rotateRight(32); v10 = blaMka(v10, v15); v5 = (v5 xor v10).rotateRight(24)
        v0 = blaMka(v0, v5); v15 = (v15 xor v0).rotateRight(16); v10 = blaMka(v10, v15); v5 = (v5 xor v10).rotateRight(63)
        // g(v1, v6, v11, v12)
        v1 = blaMka(v1, v6); v12 = (v12 xor v1).rotateRight(32); v11 = blaMka(v11, v12); v6 = (v6 xor v11).rotateRight(24)
        v1 = blaMka(v1, v6); v12 = (v12 xor v1).rotateRight(16); v11 = blaMka(v11, v12); v6 = (v6 xor v11).rotateRight(63)
        // g(v2, v7, v8, v13)
        v2 = blaMka(v2, v7); v13 = (v13 xor v2).rotateRight(32); v8 = blaMka(v8, v13); v7 = (v7 xor v8).rotateRight(24)
        v2 = blaMka(v2, v7); v13 = (v13 xor v2).rotateRight(16); v8 = blaMka(v8, v13); v7 = (v7 xor v8).rotateRight(63)
        // g(v3, v4, v9, v14)
        v3 = blaMka(v3, v4); v14 = (v14 xor v3).rotateRight(32); v9 = blaMka(v9, v14); v4 = (v4 xor v9).rotateRight(24)
        v3 = blaMka(v3, v4); v14 = (v14 xor v3).rotateRight(16); v9 = blaMka(v9, v14); v4 = (v4 xor v9).rotateRight(63)

        v[i0] = v0; v[i1] = v1; v[i2] = v2; v[i3] = v3; v[i4] = v4; v[i5] = v5; v[i6] = v6; v[i7] = v7
        v[i8] = v8; v[i9] = v9; v[i10] = v10; v[i11] = v11; v[i12] = v12; v[i13] = v13; v[i14] = v14; v[i15] = v15
    }

    private fun blaMka(x: Long, y: Long): Long = x + y + 2 * (x and 0xFFFF_FFFFL) * (y and 0xFFFF_FFFFL)
}
