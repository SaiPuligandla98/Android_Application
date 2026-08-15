package com.hcrobotics.performance.collector;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.performance.R;
import com.hcrobotics.performance.internal.Units;
import com.hcrobotics.performance.model.Metric;
import com.hcrobotics.performance.model.MetricSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports battery state, health and charging.
 *
 * <h2>Why a sticky broadcast rather than a receiver</h2>
 * {@code ACTION_BATTERY_CHANGED} is a sticky broadcast: registering a
 * {@code null} receiver against it returns the last value the system published,
 * immediately and synchronously. There is no need to register a live receiver
 * and wait for the next change — which could be minutes away on a device
 * sitting idle.
 *
 * <h2>Why battery matters on a fleet</h2>
 * Temperature and health are the two readings that predict field failures.
 * A device consistently running hot is degrading its own battery, and
 * {@code BATTERY_HEALTH_OVERHEAT} or {@code _DEAD} on a remote unit is a
 * maintenance visit that can be scheduled rather than discovered.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class BatteryCollector {

    /** Utility class; never instantiated. */
    private BatteryCollector() {
        throw new AssertionError("BatteryCollector is a utility class.");
    }

    /**
     * Reads current battery state.
     *
     * @param context any context
     * @return the Battery section of the report
     */
    @NonNull
    public static MetricSection collect(@NonNull Context context) {
        final List<Metric> metrics = new ArrayList<>();

        // Sticky broadcast: returns the last published value straight away.
        final Intent status = context.registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (status != null) {
            final int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            if (level >= 0 && scale > 0) {
                final int percent = Math.round(level * 100f / scale);
                metrics.add(new Metric(
                        context.getString(R.string.perf_battery_level),
                        percent + "%",
                        percent,
                        null));
            }

            metrics.add(new Metric(
                    context.getString(R.string.perf_battery_status),
                    describeStatus(context,
                            status.getIntExtra(BatteryManager.EXTRA_STATUS, -1))));

            metrics.add(new Metric(
                    context.getString(R.string.perf_battery_plugged),
                    describePlugged(context,
                            status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))));

            metrics.add(new Metric(
                    context.getString(R.string.perf_battery_health),
                    describeHealth(context,
                            status.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))));

            // Reported in tenths of a degree Celsius.
            final int tenthsC = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (tenthsC > 0) {
                metrics.add(new Metric(
                        context.getString(R.string.perf_battery_temperature),
                        String.format(java.util.Locale.US, "%.1f °C", tenthsC / 10f)));
            }

            // Reported in millivolts.
            final int millivolts = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (millivolts > 0) {
                metrics.add(new Metric(
                        context.getString(R.string.perf_battery_voltage),
                        String.format(java.util.Locale.US, "%.2f V", millivolts / 1000f)));
            }

            final String technology =
                    status.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (technology != null && !technology.isEmpty()) {
                metrics.add(new Metric(
                        context.getString(R.string.perf_battery_technology), technology));
            }
        }

        // ---- Capacity, available from Android 5.0 onwards -------------------
        final BatteryManager batteryManager =
                (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager != null) {
            // Reported in microampere-hours; -1 or 0 when the device does not
            // expose it, which several manufacturers do not.
            final long chargeCounter = batteryManager.getLongProperty(
                    BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (chargeCounter > 0) {
                metrics.add(new Metric(
                        context.getString(R.string.perf_battery_charge),
                        String.format(java.util.Locale.US, "%,d mAh", chargeCounter / 1000)));
            }
        }

        return new MetricSection(
                context.getString(R.string.perf_section_battery),
                R.drawable.perf_ic_battery,
                metrics);
    }

    /**
     * @param context any context
     * @param status  a {@code BATTERY_STATUS_*} constant
     * @return a readable description
     */
    @NonNull
    private static String describeStatus(@NonNull Context context, int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return context.getString(R.string.perf_battery_charging);
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return context.getString(R.string.perf_battery_discharging);
            case BatteryManager.BATTERY_STATUS_FULL:
                return context.getString(R.string.perf_battery_full);
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return context.getString(R.string.perf_battery_not_charging);
            default:
                return context.getString(R.string.perf_unknown);
        }
    }

    /**
     * @param context any context
     * @param plugged a {@code BATTERY_PLUGGED_*} constant, or 0
     * @return a readable description of the power source
     */
    @NonNull
    private static String describePlugged(@NonNull Context context, int plugged) {
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) {
            return context.getString(R.string.perf_battery_ac);
        }
        if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
            return context.getString(R.string.perf_battery_usb);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return context.getString(R.string.perf_battery_wireless);
        }
        return context.getString(R.string.perf_battery_unplugged);
    }

    /**
     * @param context any context
     * @param health  a {@code BATTERY_HEALTH_*} constant
     * @return a readable description
     */
    @NonNull
    private static String describeHealth(@NonNull Context context, int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return context.getString(R.string.perf_battery_health_good);
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return context.getString(R.string.perf_battery_health_overheat);
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return context.getString(R.string.perf_battery_health_dead);
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return context.getString(R.string.perf_battery_health_over_voltage);
            case BatteryManager.BATTERY_HEALTH_COLD:
                return context.getString(R.string.perf_battery_health_cold);
            default:
                return context.getString(R.string.perf_unknown);
        }
    }
}
