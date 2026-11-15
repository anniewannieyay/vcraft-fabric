package dev.vcraft.relay;

/** Token bucket: allows bursts up to {@code capacity}, refilling at {@code perSecond}. */
final class Bucket {
    private final double capacity;
    private final double perSecond;
    private double tokens;
    private long last = System.nanoTime();

    Bucket(double capacity, double perSecond) {
        this.capacity = capacity;
        this.perSecond = perSecond;
        this.tokens = capacity;
    }

    static Bucket perMinute(int burst, double perMinute) {
        return new Bucket(burst, perMinute / 60.0);
    }

    synchronized boolean take() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - last) / 1e9 * perSecond);
        last = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
