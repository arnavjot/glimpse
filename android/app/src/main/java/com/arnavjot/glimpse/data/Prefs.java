package com.arnavjot.glimpse.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.arnavjot.glimpse.BuildConfig;

import java.util.UUID;

/** User settings plus the anonymous device id (no account needed). */
public final class Prefs {

    public static final String KEY_API_URL = "api_url";
    public static final String KEY_PRELOAD = "preload_enabled";
    public static final String KEY_OVERLAY = "debug_overlay";
    public static final String KEY_MUTED = "muted";
    public static final String KEY_NEW_CLIP_NOTIFICATIONS = "new_clip_notifications";
    private static final String KEY_INSTALL_REFERRER_CHECKED = "install_referrer_checked";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public String apiUrl() {
        String url = prefs.getString(KEY_API_URL, BuildConfig.DEFAULT_API_URL);
        return url == null || url.trim().isEmpty() ? BuildConfig.DEFAULT_API_URL : url.trim();
    }

    /** Off turns preloading off so you can measure what it buys (an A/B switch). */
    public boolean preloadEnabled() {
        return prefs.getBoolean(KEY_PRELOAD, true);
    }

    public boolean overlayEnabled() {
        return prefs.getBoolean(KEY_OVERLAY, false);
    }

    /** The Play install referrer was read (deferred deep link): done once per install. */
    public boolean installReferrerChecked() {
        return prefs.getBoolean(KEY_INSTALL_REFERRER_CHECKED, false);
    }

    public void setInstallReferrerChecked() {
        prefs.edit().putBoolean(KEY_INSTALL_REFERRER_CHECKED, true).apply();
    }

    /** Opted in to "new clip" pushes (subscribed to the FCM topic). Off by default. */
    public boolean newClipNotifications() {
        return prefs.getBoolean(KEY_NEW_CLIP_NOTIFICATIONS, false);
    }

    /** Sound off for the whole feed, remembered across clips and launches. */
    public boolean muted() {
        return prefs.getBoolean(KEY_MUTED, false);
    }

    public void setMuted(boolean muted) {
        prefs.edit().putBoolean(KEY_MUTED, muted).apply();
    }

    /** Base of share links: the public web address if one is set, else the API server. */
    public String shareBaseUrl() {
        String configured = BuildConfig.SHARE_BASE_URL;
        return configured == null || configured.isEmpty() ? apiUrl() : configured;
    }

    /** Random and stable per install: lets the server dedupe likes without an account. */
    public synchronized String deviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }
}
