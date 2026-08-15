package com.hcrobotics.appinsights.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hcrobotics.appinsights.R;
import com.hcrobotics.appinsights.internal.Units;
import com.hcrobotics.appinsights.model.InstalledApp;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the installed-application inventory.
 *
 * <h2>Why a RecyclerView adapter and not a ScrollView of rows</h2>
 * A typical device has two to four hundred packages. Inflating a view for each
 * one costs both time and memory, and most are off screen at any moment. A
 * RecyclerView inflates roughly the number of rows that fit on screen and
 * recycles them as the list scrolls, so cost is bounded by the screen rather
 * than by the device's app count.
 *
 * <h2>Why icons are resolved during the scan, not here</h2>
 * {@code getApplicationIcon()} opens another app's resources and is far too
 * slow to call while scrolling. Resolving it once on the background scan and
 * holding the {@link android.graphics.drawable.Drawable} keeps
 * {@link #onBindViewHolder} to simple field assignment, which is what keeps
 * scrolling smooth.
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class InstalledAppAdapter
        extends RecyclerView.Adapter<InstalledAppAdapter.AppViewHolder> {

    /** Notified when a row is tapped. */
    public interface OnAppClickListener {
        /**
         * @param app the application whose row was tapped
         */
        void onAppClick(@NonNull InstalledApp app);
    }

    private final List<InstalledApp> apps = new ArrayList<>();
    private final OnAppClickListener clickListener;

    /**
     * @param clickListener notified when a row is tapped
     */
    public InstalledAppAdapter(@NonNull OnAppClickListener clickListener) {
        this.clickListener = clickListener;
    }

    /**
     * Replaces the displayed inventory.
     *
     * <p>{@code notifyDataSetChanged} is used deliberately rather than DiffUtil:
     * a re-scan replaces every row (sort order changes, the system-app filter
     * toggles), so there is no meaningful diff to compute and the cost of
     * computing one would exceed the cost of the rebind.</p>
     *
     * @param newApps the inventory to show
     */
    @SuppressWarnings("NotifyDataSetChanged")
    public void submit(@NonNull List<InstalledApp> newApps) {
        apps.clear();
        apps.addAll(newApps);
        notifyDataSetChanged();
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.apps_item_app, parent, false);
        return new AppViewHolder(view);
    }

    /** {@inheritDoc} */
    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(apps.get(position), clickListener);
    }

    /** {@inheritDoc} */
    @Override
    public int getItemCount() {
        return apps.size();
    }

    /**
     * Holds the views for one row.
     *
     * <p>The whole point of a ViewHolder is that {@code findViewById} runs once
     * per row created, rather than once per row bound. On a fast scroll that is
     * the difference between smooth and stuttering.</p>
     */
    static final class AppViewHolder extends RecyclerView.ViewHolder {

        private final ImageView icon;
        private final TextView name;
        private final TextView packageName;
        private final TextView size;
        private final TextView badge;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.apps_image_icon);
            name = itemView.findViewById(R.id.apps_text_name);
            packageName = itemView.findViewById(R.id.apps_text_package);
            size = itemView.findViewById(R.id.apps_text_size);
            badge = itemView.findViewById(R.id.apps_text_badge);
        }

        /**
         * Binds one application to this row.
         *
         * @param app      the application to show
         * @param listener notified when the row is tapped
         */
        void bind(@NonNull InstalledApp app, @NonNull OnAppClickListener listener) {
            icon.setImageDrawable(app.getIcon());
            name.setText(app.getName());
            packageName.setText(app.getPackageName());
            size.setText(Units.bytes(app.getApkSizeBytes()));

            // One badge, showing the most notable thing about this app.
            // Disabled matters more than "system", so it wins.
            if (!app.isEnabled()) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(R.string.apps_badge_disabled);
            } else if (app.isSystemApp()) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(R.string.apps_badge_system);
            } else {
                badge.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> listener.onAppClick(app));
        }
    }
}
