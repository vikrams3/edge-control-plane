package dev.vikram.analytics;

import java.util.Arrays;

/**
 * Request count, 5xx count and a latency histogram for one key in one time window.
 * Histograms with fixed buckets can be merged, so 5 s windows add up to any longer range.
 */
public final class WindowStats {

  /** Upper bounds of the latency buckets in ms; the last bucket is everything above. */
  static final long[] BOUNDS_MS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000};

  public long count;
  public long errors;
  public long[] buckets = new long[BOUNDS_MS.length + 1];

  public WindowStats add(int status, long durationMs) {
    count++;
    if (status >= 500) {
      errors++;
    }
    int i = 0;
    while (i < BOUNDS_MS.length && durationMs > BOUNDS_MS[i]) {
      i++;
    }
    buckets[i]++;
    return this;
  }

  public WindowStats merge(WindowStats other) {
    count += other.count;
    errors += other.errors;
    for (int i = 0; i < buckets.length; i++) {
      buckets[i] += other.buckets[i];
    }
    return this;
  }

  /** The bucket upper bound that q of requests fall within, in ms; -1 if no requests. */
  public long percentileMs(double q) {
    if (count == 0) {
      return -1;
    }
    long rank = (long) Math.ceil(q * count);
    long seen = 0;
    for (int i = 0; i < buckets.length; i++) {
      seen += buckets[i];
      if (seen >= rank) {
        return i < BOUNDS_MS.length ? BOUNDS_MS[i] : Long.MAX_VALUE;
      }
    }
    return Long.MAX_VALUE;
  }

  public double errorRate() {
    return count == 0 ? 0 : (double) errors / count;
  }

  @Override
  public String toString() {
    return "WindowStats[count=" + count + ", errors=" + errors + ", buckets=" + Arrays.toString(buckets) + "]";
  }
}
