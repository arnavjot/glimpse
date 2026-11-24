package com.arnavjot.glimpse.ui;

import android.os.Bundle;
import android.text.format.Formatter;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.databinding.ActivityPerformanceBinding;
import com.arnavjot.glimpse.di.ViewModelFactory;
import com.google.android.material.color.MaterialColors;
import com.arnavjot.glimpse.viewmodel.PerformanceViewModel;

import java.util.Locale;

import javax.inject.Inject;

/**
 * Shows time to first frame with preloading on vs off, rebuffers per play and the
 * disk cache's byte hit ratio, measured on this device. Toggle preloading in
 * Settings, swipe through ~30 clips each way, and compare.
 *
 * <p>Render-only: every number comes from {@link PerformanceViewModel.Report}.
 */
public class PerformanceActivity extends AppCompatActivity {

    @Inject ViewModelFactory factory;

    private ActivityPerformanceBinding binding;
    private PerformanceViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((GlimpseApp) getApplication()).component().inject(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityPerformanceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarInsets.pad(binding.scroll, SystemBarInsets.BOTTOM);
        setSupportActionBar(binding.appBar.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        viewModel = new ViewModelProvider(this, factory).get(PerformanceViewModel.class);

        viewModel.report().observe(this, this::show);
        viewModel.pendingUploads().observe(this, n -> {
            int count = n == null ? 0 : n;
            binding.pending.setText(getResources().getQuantityString(R.plurals.perf_pending, count, count));
        });
        binding.uploadNow.setOnClickListener(v -> viewModel.uploadNow());
        binding.reset.setOnClickListener(v -> viewModel.reset());
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.reload();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void show(@NonNull PerformanceViewModel.Report r) {
        binding.preloadedP50.setText(ms(r.preloadedP50));
        binding.preloadedDetail.setText(getString(R.string.perf_ttff_detail, ms(r.preloadedP95), clips(r.preloadedCount)));
        binding.coldP50.setText(ms(r.coldP50));
        binding.coldDetail.setText(getString(R.string.perf_ttff_detail, ms(r.coldP95), clips(r.coldCount)));
        // Green marks the faster median, and only when one really is faster.
        int good = ContextCompat.getColor(this, R.color.pn_success);
        int normal = MaterialColors.getColor(binding.coldP50, com.google.android.material.R.attr.colorOnSurface);
        boolean both = r.preloadedP50 != null && r.coldP50 != null;
        binding.preloadedP50.setTextColor(both && r.preloadedP50 < r.coldP50 ? good : normal);
        binding.coldP50.setTextColor(both && r.coldP50 < r.preloadedP50 ? good : normal);

        if (r.plays == 0) {
            binding.rebuffers.setText(R.string.perf_none);
            binding.rebuffersDetail.setText(R.string.perf_no_data);
        } else {
            binding.rebuffers.setText(String.format(Locale.US, "%.3f", (double) r.rebuffers / r.plays));
            binding.rebuffersDetail.setText(getString(R.string.perf_rebuffers_detail, r.rebuffers, r.plays));
        }

        if (r.byteHitRatio < 0) {
            binding.cache.setText(R.string.perf_none);
            binding.cacheDetail.setText(R.string.perf_no_data);
        } else {
            binding.cache.setText(getString(R.string.perf_percent, Math.round(r.byteHitRatio * 100)));
            binding.cacheDetail.setText(getString(R.string.perf_cache_detail,
                    Formatter.formatShortFileSize(this, r.cacheBytes),
                    Formatter.formatShortFileSize(this, r.networkBytes)));
        }
    }

    @NonNull
    private String clips(int n) {
        return getResources().getQuantityString(R.plurals.clips, n, n);
    }

    @NonNull
    private String ms(@Nullable Long value) {
        return value == null ? getString(R.string.perf_none) : getString(R.string.perf_ms, value);
    }
}
