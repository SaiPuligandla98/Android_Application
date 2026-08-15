package com.hcrobotics.performance.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One labelled reading in the performance report.
 *
 * <h2>Why every metric is the same shape</h2>
 * Memory, storage, battery and network report very different things, but the
 * screen renders them identically: a label, a value, and optionally a bar
 * showing a proportion. Modelling them as one type means the UI needs a single
 * row layout and a single binding path, rather than a branch per category.
 *
 * <p>Adding a new reading is then a matter of producing one more {@code Metric}
 * in a collector — no UI change at all.</p>
 *
 * <h2>Why the percentage is optional</h2>
 * "RAM used: 4.2 GB of 8.0 GB" has a meaningful proportion and earns a bar.
 * "Android version: 16" does not. Rather than invent a number for the second
 * case, {@link #getPercent()} returns {@link #NO_PERCENT} and the row simply
 * omits the bar.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class Metric {

    /** Returned by {@link #getPercent()} when a proportion is meaningless. */
    public static final int NO_PERCENT = -1;

    private final String label;
    private final String value;
    private final int percent;
    private final String detail;

    /**
     * Creates a metric with no proportion bar.
     *
     * @param label short name, e.g. "Android version"
     * @param value the reading itself, already formatted for display
     */
    public Metric(@NonNull String label, @NonNull String value) {
        this(label, value, NO_PERCENT, null);
    }

    /**
     * Creates a metric with a proportion bar.
     *
     * @param label   short name, e.g. "Memory used"
     * @param value   the reading, already formatted
     * @param percent 0-100 for the bar, or {@link #NO_PERCENT} for none
     * @param detail  optional supporting line beneath the value
     */
    public Metric(@NonNull String label,
                  @NonNull String value,
                  int percent,
                  @Nullable String detail) {
        this.label = label;
        this.value = value;
        // Clamped rather than rejected: a momentary reading slightly outside
        // the range should render sensibly, not crash a diagnostics screen.
        this.percent = percent == NO_PERCENT ? NO_PERCENT : Math.max(0, Math.min(100, percent));
        this.detail = detail;
    }

    /** @return the metric's short name */
    @NonNull
    public String getLabel() {
        return label;
    }

    /** @return the reading, formatted for display */
    @NonNull
    public String getValue() {
        return value;
    }

    /** @return 0-100 for a proportion bar, or {@link #NO_PERCENT} */
    public int getPercent() {
        return percent;
    }

    /** @return an optional supporting line, or {@code null} */
    @Nullable
    public String getDetail() {
        return detail;
    }

    /** @return {@code true} if this metric should show a proportion bar */
    public boolean hasBar() {
        return percent != NO_PERCENT;
    }
}
