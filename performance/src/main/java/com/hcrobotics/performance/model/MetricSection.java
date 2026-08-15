package com.hcrobotics.performance.model;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A titled group of related {@link Metric}s — Memory, Storage, Battery and so on.
 *
 * <h2>Why grouping is part of the model rather than the layout</h2>
 * A flat list of thirty readings is unusable: the eye has nothing to anchor to.
 * Grouping is what makes the screen scannable, and expressing it in the model
 * means a collector decides how its own output is organised, rather than the
 * Activity having to know which reading belongs where.
 *
 * <p>Each collector returns exactly one section, so adding a category is a new
 * collector and one line in the Activity — nothing else changes.</p>
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class MetricSection {

    private final String title;
    private final int iconRes;
    private final List<Metric> metrics;

    /**
     * Creates a section.
     *
     * @param title   heading, e.g. "Memory"
     * @param iconRes drawable shown beside the heading
     * @param metrics the readings in this group
     */
    public MetricSection(@NonNull String title,
                         @DrawableRes int iconRes,
                         @NonNull List<Metric> metrics) {
        this.title = title;
        this.iconRes = iconRes;
        // Defensive copy: the caller must not be able to mutate a section after
        // it has been handed to the UI, which reads it on another pass.
        this.metrics = Collections.unmodifiableList(new ArrayList<>(metrics));
    }

    /** @return the section heading */
    @NonNull
    public String getTitle() {
        return title;
    }

    /** @return drawable resource for the section icon */
    @DrawableRes
    public int getIconRes() {
        return iconRes;
    }

    /** @return the readings, unmodifiable */
    @NonNull
    public List<Metric> getMetrics() {
        return metrics;
    }
}
