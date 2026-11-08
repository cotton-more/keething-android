# The reference Argon2, vendored

From github.com/P-H-C/phc-winner-argon2 at commit f57e61e19229e23c4445b85494dbf7c07de721cb (2021-06-25), under
CC0 1.0 or Apache 2.0 (`LICENSE`). Only what Keething builds is here: `include/argon2.h`, `src/core.c`, `src/core.h`,
`src/ref.c` (the portable code; `opt.c` needs SSE, which phones lack), `src/thread.h` (included by `core.c`, empty
with `ARGON2_NO_THREADS`) and BLAKE2b (`src/blake2/`). Not here: `argon2.c` (Keething's `keething_argon2.c` runs
the steps of its `argon2_ctx` itself), `thread.c` (`keething_argon2.c` fills the lanes with workers that live as long
as the derivation: the reference code starts new threads for every slice, which Android puts on slow cores), the
encoding, the tools and the tests.

One change, marked "Keething" in the code (keething-spec unlock.faster-argon2 AC4): `argon2_instance_t` has a
`given_up` flag (`core.h`); once it is set, `fill_segment` in `ref.c` returns at its next check, made every 1,024
blocks (about a millisecond), and `keething_argon2.c` starts no more slices. The reference code has no way to stop a
derivation; between slices alone a given-up derivation of 1 GiB would run on for about 100 ms. Nothing else differs
from the commit above.
