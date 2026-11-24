package com.arnavjot.glimpse.config;

import androidx.annotation.NonNull;

/**
 * The app's feature flags. Each default is what the app does before it has ever
 * fetched the config (first launch, offline), so every default is the safe, shipped
 * behaviour.
 */
public enum Flag {
    /** Preload upcoming clips. Killing it saves bandwidth if preloading misbehaves. */
    PRELOAD("preload", true),
    /** Attach a poster card image when sharing (else text and link only). */
    SHARE_CARDS("share_cards", true),
    /** New-clip push notifications: the Settings switch and incoming messages. */
    PUSH_NOTIFICATIONS("push_notifications", true),
    /** Posting clips. The server enforces it too; the app hides the button when it's off. */
    UPLOADS("uploads", true);

    @NonNull public final String key;
    public final boolean defaultOn;

    Flag(@NonNull String key, boolean defaultOn) {
        this.key = key;
        this.defaultOn = defaultOn;
    }
}
