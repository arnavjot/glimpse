package com.arnavjot.glimpse.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import okhttp3.Authenticator;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Attaches the access token to API calls and keeps it working:
 * <ul>
 *   <li>Before sending: a token that expires within {@link #EARLY_REFRESH_MILLIS} is
 *       refreshed first, so most requests never see a 401.</li>
 *   <li>On a 401 anyway (clock skew, revoked): refresh once and retry once. A second 401
 *       is returned to the caller; a refused refresh has already signed out.</li>
 * </ul>
 */
public final class AuthInterceptor implements Interceptor, Authenticator {

    static final long EARLY_REFRESH_MILLIS = 30_000;
    private static final String HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    private final SessionManager sessions;

    public AuthInterceptor(@NonNull SessionManager sessions) {
        this.sessions = sessions;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Session s = sessions.current();
        if (s == null) return chain.proceed(chain.request());
        String token = s.accessToken;
        if (sessions.expiresWithin(s, EARLY_REFRESH_MILLIS)) {
            String fresh = sessions.refreshAfter(token);
            if (fresh != null) token = fresh;
            else if (sessions.current() == null) return chain.proceed(chain.request()); // signed out
        }
        return chain.proceed(chain.request().newBuilder().header(HEADER, BEARER + token).build());
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, @NonNull Response response) {
        String sent = response.request().header(HEADER);
        if (sent == null || !sent.startsWith(BEARER)) return null; // wasn't signed in: nothing to fix
        if (priorResponses(response) >= 1) return null;           // already retried once
        String fresh = sessions.refreshAfter(sent.substring(BEARER.length()));
        return fresh == null ? null : response.request().newBuilder().header(HEADER, BEARER + fresh).build();
    }

    private static int priorResponses(@NonNull Response response) {
        int n = 0;
        for (Response r = response.priorResponse(); r != null; r = r.priorResponse()) n++;
        return n;
    }
}
