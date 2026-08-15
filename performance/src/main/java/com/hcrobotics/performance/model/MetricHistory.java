package com.hcrobotics.performance.model;

import androidx.annotation.NonNull;

/**
 * A fixed-size rolling window of recent samples, for the live graphs.
 *
 * <h2>Why a ring buffer rather than a list</h2>
 * A graph shows the last N seconds and nothing more. A growing list would need
 * trimming on every sample — either shifting every element down (O(n) per
 * sample, forever) or repeatedly reallocating.
 *
 * <p>A ring buffer writes each sample into one slot and moves a cursor. Adding
 * a sample is O(1), memory never grows, and no allocation happens after
 * construction — which matters when this runs once a second for as long as the
 * screen is open.</p>
 *
 * <h2>Why the maximum is tracked incrementally</h2>
 * The graph must scale to its tallest sample. Rescanning the whole buffer on
 * every frame would be wasteful, so {@link #getMax()} is maintained as samples
 * arrive.
 *
 * <p>One subtlety: when the tallest sample eventually rolls out of the window,
 * the cached maximum is stale. It is recomputed only then — rare, and cheap
 * when it happens, rather than paying for it every frame.</p>
 *
 * <h2>Threading</h2>
 * Not thread-safe by design. Samples are added on the main thread by the
 * refresh loop and read on the main thread while drawing, so there is nothing
 * to synchronise and no lock to pay for.
 *
 * @author HC Robotics
 * @since 1.9.0
 */
public final class MetricHistory {

    private final float[] samples;
    private int count;
    private int writeIndex;
    private float max;

    /**
     * @param capacity how many samples the window holds; at one sample per
     *                 second this is the number of seconds shown
     */
    public MetricHistory(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.samples = new float[capacity];
    }

    /**
     * Records one sample, discarding the oldest when the window is full.
     *
     * @param value the sample; negative values are clamped to zero, since every
     *              metric graphed here is a magnitude
     */
    public void add(float value) {
        final float sample = Math.max(0f, value);

        // Note the value about to be overwritten, to decide whether the cached
        // maximum is still valid.
        final boolean windowFull = count == samples.length;
        final float evicted = windowFull ? samples[writeIndex] : Float.NaN;

        samples[writeIndex] = sample;
        writeIndex = (writeIndex + 1) % samples.length;
        if (!windowFull) {
            count++;
        }

        if (sample >= max) {
            max = sample;
        } else if (windowFull && evicted >= max) {
            // The tallest sample has just rolled out of the window, so the
            // cached maximum no longer reflects the data. This is the only
            // case that requires a rescan.
            recomputeMax();
        }
    }

    /** Recomputes the maximum by scanning the window. */
    private void recomputeMax() {
        float found = 0f;
        for (int i = 0; i < count; i++) {
            found = Math.max(found, samples[i]);
        }
        max = found;
    }

    /**
     * Copies the samples out in chronological order, oldest first.
     *
     * <p>The internal buffer is circular, so its raw order is meaningless to a
     * caller. This returns them straightened out, ready to plot left to right.</p>
     *
     * @return the samples currently held, oldest first
     */
    @NonNull
    public float[] toOrderedArray() {
        final float[] ordered = new float[count];
        if (count < samples.length) {
            // Window not yet full: samples sit at 0..count-1 already in order.
            System.arraycopy(samples, 0, ordered, 0, count);
        } else {
            // Full: the oldest sample is the one about to be overwritten.
            final int tailLength = samples.length - writeIndex;
            System.arraycopy(samples, writeIndex, ordered, 0, tailLength);
            System.arraycopy(samples, 0, ordered, tailLength, writeIndex);
        }
        return ordered;
    }

    /** @return the largest sample currently in the window, or {@code 0} */
    public float getMax() {
        return max;
    }

    /** @return the most recent sample, or {@code 0} if none has been added */
    public float getLatest() {
        if (count == 0) {
            return 0f;
        }
        final int lastIndex = (writeIndex - 1 + samples.length) % samples.length;
        return samples[lastIndex];
    }

    /** @return how many samples are currently held */
    public int size() {
        return count;
    }
}
