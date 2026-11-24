package com.arnavjot.glimpse.server.experiments;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * How each variant of an experiment is doing, with a significance check per metric.
 *
 * <ul>
 *   <li>Only events after a device's first exposure count, and every exposed device counts
 *       (one that never pressed play adds zero watch time): intent-to-treat, so a variant
 *       that makes people leave isn't flattered by dropping them.</li>
 *   <li>Completion rate: two-proportion z-test. Watch time per device: Welch's t-test
 *       (normal approximation, fine at the sample sizes worth reading).</li>
 *   <li>{@code significant} means p &lt; 0.05. Below {@link #MIN_DEVICES} devices per variant
 *       the report says so instead of declaring a winner.</li>
 * </ul>
 */
@Component
public class ExperimentReport {

    static final int MIN_DEVICES = 30;
    static final double ALPHA = 0.05;

    public record Variant(long devices, long plays, long completions, Double completionRate,
                          double watchMsPerDevice) {}

    public record Comparison(Double lift, Double pValue, boolean significant) {}

    public record Report(String experiment, Variant control, Variant treatment,
                         Comparison completionRate, Comparison watchMsPerDevice, String verdict) {}

    private record Agg(long devices, long plays, long completions, double meanWatch, double varWatch) {}

    private final JdbcTemplate jdbc;

    public ExperimentReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Report build(String key) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM experiments WHERE key = ?)", Boolean.class, key);
        if (!Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such experiment");
        Map<String, Agg> by = new HashMap<>();
        jdbc.query("""
                WITH per_device AS (
                    SELECT e.variant, e.device_id,
                           count(p.*) FILTER (WHERE p.type = 'play_start') AS plays,
                           count(p.*) FILTER (WHERE p.type = 'complete')   AS completions,
                           coalesce(sum(p.value_ms) FILTER (WHERE p.type = 'watch'), 0) AS watch_ms
                      FROM experiment_exposures e
                      LEFT JOIN playback_events p
                             ON p.device_id = e.device_id AND p.occurred_at >= e.first_exposed
                     WHERE e.experiment_key = ?
                     GROUP BY e.variant, e.device_id)
                SELECT variant, count(*), sum(plays), sum(completions),
                       avg(watch_ms)::float8, coalesce(var_samp(watch_ms), 0)::float8
                  FROM per_device GROUP BY variant""", rs -> {
            by.put(rs.getString(1), new Agg(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5), rs.getDouble(6)));
        }, key);
        Agg c = by.getOrDefault(ExperimentService.CONTROL, new Agg(0, 0, 0, 0, 0));
        Agg t = by.getOrDefault(ExperimentService.TREATMENT, new Agg(0, 0, 0, 0, 0));

        Comparison completion = compareProportions(c.completions, c.plays, t.completions, t.plays);
        Comparison watch = compareMeans(c.meanWatch, c.varWatch, c.devices, t.meanWatch, t.varWatch, t.devices);
        String verdict;
        if (c.devices < MIN_DEVICES || t.devices < MIN_DEVICES) {
            verdict = "not enough data yet (need " + MIN_DEVICES + " devices per variant)";
        } else if (watch.significant()) {
            verdict = watch.lift() > 0 ? "treatment wins on watch time" : "treatment loses on watch time";
        } else {
            verdict = "no significant difference in watch time yet";
        }
        return new Report(key, variant(c), variant(t), completion, watch, verdict);
    }

    private static Variant variant(Agg a) {
        return new Variant(a.devices, a.plays, a.completions,
                a.plays == 0 ? null : round(a.completions / (double) a.plays, 4), round(a.meanWatch, 1));
    }

    /** Two-proportion z-test (pooled). */
    static Comparison compareProportions(long x1, long n1, long x2, long n2) {
        if (n1 == 0 || n2 == 0) return new Comparison(null, null, false);
        double p1 = x1 / (double) n1;
        double p2 = x2 / (double) n2;
        double pooled = (x1 + x2) / (double) (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        Double lift = p1 == 0 ? null : round((p2 - p1) / p1, 4);
        if (se == 0) return new Comparison(lift, null, false);
        double p = twoSidedP((p2 - p1) / se);
        return new Comparison(lift, round(p, 6), p < ALPHA);
    }

    /** Welch's t-test with a normal approximation for the p-value. */
    static Comparison compareMeans(double m1, double v1, long n1, double m2, double v2, long n2) {
        if (n1 < 2 || n2 < 2) return new Comparison(null, null, false);
        double se = Math.sqrt(v1 / n1 + v2 / n2);
        Double lift = m1 == 0 ? null : round((m2 - m1) / m1, 4);
        if (se == 0) return new Comparison(lift, null, false);
        double p = twoSidedP((m2 - m1) / se);
        return new Comparison(lift, round(p, 6), p < ALPHA);
    }

    static double twoSidedP(double z) {
        return erfc(Math.abs(z) / Math.sqrt(2));
    }

    /** Complementary error function (Numerical Recipes erfcc, error below 1.2e-7). */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
