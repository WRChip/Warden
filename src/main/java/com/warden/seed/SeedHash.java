package com.warden.seed;

import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;

/**
 * A value derived one-way from the world seed. Loot-table-seed cracking works by capturing a
 * chest's rolled seed and brute-forcing the world seed that would produce it; XORing every
 * rolled seed with this hash before it's stored breaks that correlation, since recovering the
 * world seed from the hash means inverting a PRNG, not searching a keyspace.
 *
 * Computed once at server start from the real world seed. Never itself written to disk or sent
 * to a client.
 */
public final class SeedHash {

    // one xoroshiro round is already considered practically irreversible; this just adds margin
    private static final int DISCARD_ROUNDS = 128;

    private static volatile long hash;

    private SeedHash() {
    }

    public static void precompute(long worldSeed) {
        Xoroshiro128PlusPlusRandom random = new Xoroshiro128PlusPlusRandom(worldSeed);
        for (int i = 0; i < DISCARD_ROUNDS; i++) {
            random.nextLong();
        }
        hash = random.nextLong();
    }

    public static long get() {
        return hash;
    }
}
