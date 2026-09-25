package de.farmpulse.rpsim.common;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Single source of randomness for all fact-layer rolls (spawns, bands, NPC bids). Injected so tests can
 * use a seeded instance and assert statistical properties deterministically.
 */
public class RandomSource {

    private final SplittableRandom random;

    public RandomSource(long seed) {
        this.random = new SplittableRandom(seed);
    }

    public RandomSource() {
        this.random = new SplittableRandom();
    }

    public synchronized double nextDouble() {
        return random.nextDouble();
    }

    /** Uniform value in [min, max]. */
    public double uniform(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + nextDouble() * (max - min);
    }

    /** Uniform int in [min, max] (inclusive). */
    public synchronized int intBetween(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    public boolean chance(double probability) {
        return nextDouble() < probability;
    }

    public synchronized long nextLong() {
        return random.nextLong();
    }

    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("cannot pick from empty list");
        }
        return items.get(intBetween(0, items.size() - 1));
    }

    /** Weighted pick; entries with weight <= 0 are never chosen. */
    public <T> T weighted(Map<T, Double> weights) {
        double total = weights.values().stream().mapToDouble(w -> Math.max(0, w)).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("no positive weight");
        }
        double r = nextDouble() * total;
        T last = null;
        for (Map.Entry<T, Double> e : weights.entrySet()) {
            double w = Math.max(0, e.getValue());
            if (w <= 0) {
                continue;
            }
            last = e.getKey();
            r -= w;
            if (r < 0) {
                return e.getKey();
            }
        }
        return last;
    }

    /** Deterministic generator derived from a seed (e.g. a character seed for NPC bids). */
    public static RandomSource seeded(long seed) {
        return new RandomSource(seed);
    }
}
