package com.hcrobotics.performance.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.performance.model.MetricHistory;

/**
 * A live line graph of recent samples, drawn directly onto the Canvas.
 *
 * <h2>Why not a charting library</h2>
 * MPAndroidChart and its peers are excellent and vastly more than this needs.
 * They add a megabyte or more to the APK, bring their own dependency tree that
 * can collide with a host app's, and exist to support interaction — zoom, pan,
 * legends, tooltips — that a rolling performance sparkline has no use for.
 *
 * <p>The whole graph is roughly a hundred lines of {@link Canvas} work with no
 * dependency at all, which keeps this module as drop-in as the rest of it.</p>
 *
 * <h2>Why nothing is allocated in onDraw</h2>
 * {@code onDraw} runs on every frame. Allocating a {@link Paint} or {@link Path}
 * there produces garbage at 60 Hz, and the resulting collections are a classic
 * cause of visible stutter — a performance monitor that itself causes jank is
 * self-defeating.
 *
 * <p>Every object is therefore created once in {@link #init()} and reused, and
 * the gradient is rebuilt only when the view's height actually changes.</p>
 *
 * <h2>Scaling</h2>
 * The vertical axis is scaled to the tallest sample in the window rather than
 * to a fixed maximum. A memory graph pinned to total RAM would be a flat line
 * near the bottom; scaling to what actually happened is what makes the shape
 * of the change visible, which is the entire point of the graph.
 *
 * @author HC Robotics
 * @since 1.9.0
 */
public final class SparklineView extends View {

    /** Stroke width of the trace, in dp. */
    private static final float LINE_WIDTH_DP = 2f;

    /** Alpha applied to the area fill beneath the trace. */
    private static final int FILL_ALPHA_TOP = 90;

    /** Inset above the tallest point so the trace is never clipped. */
    private static final float TOP_PADDING_FRACTION = 0.12f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    @ColorInt
    private int traceColor = Color.CYAN;

    /** The data being plotted; null until the owner supplies it. */
    @Nullable
    private MetricHistory history;

    /** Height the gradient was built for, so it is rebuilt only when needed. */
    private int gradientHeight = -1;

    public SparklineView(@NonNull Context context) {
        super(context);
        init();
    }

    public SparklineView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SparklineView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /** Configures the reusable paints. Called once per view. */
    private void init() {
        final float density = getResources().getDisplayMetrics().density;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(LINE_WIDTH_DP * density);
        // Rounded joins stop the trace developing sharp spikes at each sample,
        // which would misrepresent the data as noisier than it is.
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        baselinePaint.setStyle(Paint.Style.STROKE);
        baselinePaint.setStrokeWidth(density);
        baselinePaint.setColor(Color.argb(40, 128, 128, 128));
    }

    /**
     * Sets the colour of the trace and its fill.
     *
     * @param color the line colour; the fill is a fade of the same hue
     */
    public void setTraceColor(@ColorInt int color) {
        this.traceColor = color;
        linePaint.setColor(color);
        // Force the gradient to be rebuilt with the new colour.
        gradientHeight = -1;
        invalidate();
    }

    /**
     * Attaches the data to plot.
     *
     * <p>The view holds a reference rather than a copy: the owner adds samples
     * to the same object and calls {@link #invalidate()}, so there is nothing
     * to copy and no chance of the two drifting apart.</p>
     *
     * @param history the rolling sample window
     */
    public void setHistory(@NonNull MetricHistory history) {
        this.history = history;
        invalidate();
    }

    /** {@inheritDoc} */
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        final int width = getWidth();
        final int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        // A faint baseline, so an empty or flat graph still reads as a graph
        // rather than as a rendering failure.
        canvas.drawLine(0, height - 1f, width, height - 1f, baselinePaint);

        if (history == null || history.size() < 2) {
            // One sample cannot describe a trend; wait for the second.
            return;
        }

        final float[] samples = history.toOrderedArray();

        /*
         * Scale to the tallest sample in the window, not to a fixed ceiling.
         *
         * A memory graph scaled to total RAM would sit as a flat line near the
         * bottom and show nothing. Scaling to what actually happened is what
         * makes the shape of the change visible.
         *
         * The floor of 1 avoids dividing by zero when every sample is zero -
         * an idle network, for instance, which is a perfectly normal state.
         */
        final float max = Math.max(1f, history.getMax());
        final float usableHeight = height * (1f - TOP_PADDING_FRACTION);
        final float stepX = width / (float) (samples.length - 1);

        rebuildGradientIfNeeded(height);

        linePath.reset();
        fillPath.reset();

        for (int i = 0; i < samples.length; i++) {
            final float x = i * stepX;
            final float y = height - (samples[i] / max) * usableHeight;

            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, height);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }

        // Close the fill down to the baseline so it reads as an area, not a
        // second stray line.
        fillPath.lineTo((samples.length - 1) * stepX, height);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }

    /**
     * Rebuilds the vertical fade, but only when the height has changed.
     *
     * <p>A {@link LinearGradient} depends on the view's height, so it cannot be
     * built in the constructor. Rebuilding it on every frame would allocate a
     * shader 60 times a second; caching it against the height it was built for
     * means it is rebuilt on resize and never otherwise.</p>
     *
     * @param height the current view height in pixels
     */
    private void rebuildGradientIfNeeded(int height) {
        if (gradientHeight == height) {
            return;
        }
        gradientHeight = height;
        fillPaint.setShader(new LinearGradient(
                0, 0, 0, height,
                Color.argb(FILL_ALPHA_TOP,
                        Color.red(traceColor), Color.green(traceColor), Color.blue(traceColor)),
                Color.argb(0,
                        Color.red(traceColor), Color.green(traceColor), Color.blue(traceColor)),
                Shader.TileMode.CLAMP));
    }
}
