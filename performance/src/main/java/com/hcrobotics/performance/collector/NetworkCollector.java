package com.hcrobotics.performance.collector;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.hcrobotics.performance.R;
import com.hcrobotics.performance.internal.Units;
import com.hcrobotics.performance.model.Metric;
import com.hcrobotics.performance.model.MetricSection;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reports connectivity, Wi-Fi link quality and data usage.
 *
 * <h2>The Wi-Fi SSID restriction</h2>
 * From Android 8.1 the network name (SSID) is only readable with location
 * permission, because the set of visible networks can be used to locate a
 * device. Without it, {@link WifiInfo#getSSID()} returns
 * {@code "<unknown ssid>"} — not an error, just a redaction.
 *
 * <p>This collector detects that and says so plainly, rather than displaying
 * the literal placeholder and leaving someone to wonder whether the Wi-Fi is
 * broken. Everything else about the link — speed, signal, frequency, IP — needs
 * no permission at all.</p>
 *
 * <h2>Why data totals are since boot</h2>
 * {@link TrafficStats} counters reset when the device restarts. Per-app
 * historical usage needs {@code NetworkStatsManager} and the special
 * "usage access" grant; the since-boot totals here need nothing and are
 * enough to answer "is this device transferring anything at all?".
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class NetworkCollector {

    /** Utility class; never instantiated. */
    private NetworkCollector() {
        throw new AssertionError("NetworkCollector is a utility class.");
    }

    /**
     * Reads current network state.
     *
     * @param context any context
     * @return the Network section of the report
     */
    @NonNull
    public static MetricSection collect(@NonNull Context context) {
        final List<Metric> metrics = new ArrayList<>();

        final ConnectivityManager connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        boolean onWifi = false;

        if (connectivity != null) {
            final Network active = connectivity.getActiveNetwork();
            final NetworkCapabilities capabilities = active == null
                    ? null : connectivity.getNetworkCapabilities(active);

            if (capabilities == null) {
                metrics.add(new Metric(
                        context.getString(R.string.perf_net_connection),
                        context.getString(R.string.perf_net_offline)));
            } else {
                onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                metrics.add(new Metric(
                        context.getString(R.string.perf_net_connection),
                        describeTransport(context, capabilities)));

                metrics.add(new Metric(
                        context.getString(R.string.perf_net_internet),
                        context.getString(
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                        ? R.string.perf_net_validated
                                        : R.string.perf_net_not_validated)));

                // Reported in kilobits per second by the platform.
                if (capabilities.getLinkDownstreamBandwidthKbps() > 0) {
                    metrics.add(new Metric(
                            context.getString(R.string.perf_net_bandwidth),
                            String.format(Locale.US, "%.1f Mbps down / %.1f Mbps up",
                                    capabilities.getLinkDownstreamBandwidthKbps() / 1000f,
                                    capabilities.getLinkUpstreamBandwidthKbps() / 1000f),
                            Metric.NO_PERCENT,
                            context.getString(R.string.perf_net_bandwidth_detail)));
                }

                metrics.add(new Metric(
                        context.getString(R.string.perf_net_metered),
                        context.getString(
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                                        ? R.string.perf_no
                                        : R.string.perf_yes),
                        Metric.NO_PERCENT,
                        context.getString(R.string.perf_net_metered_detail)));
            }
        }

        if (onWifi) {
            addWifiDetails(context, metrics);
        }

        addAddresses(context, metrics);
        addTrafficTotals(context, metrics);

        return new MetricSection(
                context.getString(R.string.perf_section_network),
                R.drawable.perf_ic_network,
                metrics);
    }

    /**
     * Adds Wi-Fi link quality readings.
     *
     * @param context any context
     * @param metrics list to append to
     */
    private static void addWifiDetails(@NonNull Context context,
                                       @NonNull List<Metric> metrics) {
        final WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return;
        }

        final WifiInfo info = wifi.getConnectionInfo();
        if (info == null) {
            return;
        }

        // ---- SSID, subject to the location restriction ----------------------
        final boolean hasLocation = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        String ssid = info.getSSID();
        if (ssid != null) {
            // The platform wraps it in quotes.
            ssid = ssid.replace("\"", "");
        }
        final boolean redacted = ssid == null
                || ssid.isEmpty()
                || ssid.equalsIgnoreCase("<unknown ssid>");

        metrics.add(new Metric(
                context.getString(R.string.perf_net_ssid),
                redacted ? context.getString(R.string.perf_net_ssid_hidden) : ssid,
                Metric.NO_PERCENT,
                redacted && !hasLocation
                        ? context.getString(R.string.perf_net_ssid_needs_location)
                        : null));

        // ---- Signal strength -------------------------------------------------
        // RSSI is in dBm and runs roughly -30 (excellent) to -90 (unusable).
        // calculateSignalLevel maps it onto a 0-4 scale, which is what the
        // status bar icon shows, so the reading matches what the user can see.
        final int rssi = info.getRssi();
        final int bars = WifiManager.calculateSignalLevel(rssi, 5);
        metrics.add(new Metric(
                context.getString(R.string.perf_net_signal),
                rssi + " dBm",
                bars * 25,
                context.getString(R.string.perf_net_signal_detail, bars, 4)));

        if (info.getLinkSpeed() > 0) {
            metrics.add(new Metric(
                    context.getString(R.string.perf_net_link_speed),
                    info.getLinkSpeed() + " Mbps"));
        }

        // Frequency distinguishes the 2.4 GHz and 5 GHz bands - the usual
        // explanation for a strong signal that is nonetheless slow.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.getFrequency() > 0) {
            final int mhz = info.getFrequency();
            metrics.add(new Metric(
                    context.getString(R.string.perf_net_frequency),
                    mhz + " MHz",
                    Metric.NO_PERCENT,
                    mhz > 4000 ? "5 GHz band" : "2.4 GHz band"));
        }
    }

    /**
     * Adds the device's IPv4 addresses.
     *
     * <p>Read from {@link NetworkInterface} rather than {@code WifiInfo}, so it
     * works on Ethernet and mobile data too, and avoids the deprecated
     * integer-encoded address that only ever supported IPv4 on Wi-Fi.</p>
     *
     * @param context any context
     * @param metrics list to append to
     */
    private static void addAddresses(@NonNull Context context,
                                     @NonNull List<Metric> metrics) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        metrics.add(new Metric(
                                context.getString(R.string.perf_net_ip, nif.getDisplayName()),
                                address.getHostAddress()));
                    }
                }
            }
        } catch (Exception e) {
            // Enumeration can fail transiently while an interface is
            // reconfiguring. Not worth failing the whole report over.
            metrics.add(new Metric(
                    context.getString(R.string.perf_net_ip, "-"),
                    context.getString(R.string.perf_unavailable)));
        }
    }

    /**
     * Adds since-boot data totals.
     *
     * @param context any context
     * @param metrics list to append to
     */
    private static void addTrafficTotals(@NonNull Context context,
                                         @NonNull List<Metric> metrics) {
        final long received = TrafficStats.getTotalRxBytes();
        final long transmitted = TrafficStats.getTotalTxBytes();

        // UNSUPPORTED is returned on devices that do not track this at all.
        if (received == TrafficStats.UNSUPPORTED || transmitted == TrafficStats.UNSUPPORTED) {
            return;
        }

        metrics.add(new Metric(
                context.getString(R.string.perf_net_received),
                Units.bytes(received),
                Metric.NO_PERCENT,
                context.getString(R.string.perf_net_since_boot)));

        metrics.add(new Metric(
                context.getString(R.string.perf_net_transmitted),
                Units.bytes(transmitted),
                Metric.NO_PERCENT,
                context.getString(R.string.perf_net_since_boot)));

        // This app's own usage, which is readable for our own UID.
        final int uid = android.os.Process.myUid();
        final long appRx = TrafficStats.getUidRxBytes(uid);
        final long appTx = TrafficStats.getUidTxBytes(uid);
        if (appRx != TrafficStats.UNSUPPORTED && appRx >= 0) {
            metrics.add(new Metric(
                    context.getString(R.string.perf_net_app_usage),
                    Units.bytes(appRx + appTx),
                    Metric.NO_PERCENT,
                    context.getString(R.string.perf_net_app_usage_detail)));
        }
    }

    /**
     * @param context      any context
     * @param capabilities capabilities of the active network
     * @return a readable transport name
     */
    @NonNull
    private static String describeTransport(@NonNull Context context,
                                            @NonNull NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return context.getString(R.string.perf_net_wifi);
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return context.getString(R.string.perf_net_cellular);
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return context.getString(R.string.perf_net_ethernet);
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return context.getString(R.string.perf_net_vpn);
        }
        return context.getString(R.string.perf_unknown);
    }
}
