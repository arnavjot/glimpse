package com.arnavjot.glimpse.analytics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Nearest-rank percentiles, the same definition the backend uses. */
public final class Percentiles {

    private Percentiles() {}

    /** @return the p-th percentile (0 < p <= 100), or null for no samples. */
    @Nullable
    public static Long of(List<Long> values, int p) {
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(1, Math.min(sorted.size(), rank)) - 1);
    }
}
