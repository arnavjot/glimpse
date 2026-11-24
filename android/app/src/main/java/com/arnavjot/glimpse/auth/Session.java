package com.arnavjot.glimpse.auth;

import androidx.annotation.NonNull;

/** A signed-in creator: who, and the tokens that prove it. Immutable. */
public final class Session {

    @NonNull public final String userId;
    @NonNull public final String handle;
    @NonNull public final String accessToken;
    @NonNull public final String refreshToken;
    /** When the access token expires (epoch millis). */
    public final long accessExpiresAtMillis;

    public Session(@NonNull String userId, @NonNull String handle, @NonNull String accessToken,
                   @NonNull String refreshToken, long accessExpiresAtMillis) {
        this.userId = userId;
        this.handle = handle;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAtMillis = accessExpiresAtMillis;
    }
}
