package com.arnavjot.glimpse.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.arnavjot.glimpse.network.AuthApi;
import com.arnavjot.glimpse.pojo.AuthDto;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.function.LongSupplier;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * The creator's session: sign up, sign in, sign out, and keeping the access token fresh.
 *
 * <p>Refresh is single-flight: when several requests find the token expired at once,
 * the first refreshes and the others reuse its result. That matters because the server
 * rotates refresh tokens and treats a second use of one as theft (ending every session).
 *
 * <p>A refresh the server refuses (401: revoked or expired) signs out. A refresh that
 * fails for any other reason (offline, server error) keeps the session for later.
 */
public class SessionManager {

    /** A sign-in problem to show the user: the server's message, or a network one. */
    public static final class AuthException extends Exception {
        public final int httpCode; // 0: no response (network)

        AuthException(int httpCode, @NonNull String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    private final AuthApi api;
    private final SessionStore store;
    private final LongSupplier clock;
    private final Gson gson = new Gson();
    private final MutableLiveData<Session> signedIn;
    private volatile Session session;

    public SessionManager(@NonNull AuthApi api, @NonNull SessionStore store, @NonNull LongSupplier clock) {
        this.api = api;
        this.store = store;
        this.clock = clock;
        this.session = store.load();
        this.signedIn = new MutableLiveData<>(session);
    }

    /** The current session, or null when signed out. */
    @Nullable
    public Session current() {
        return session;
    }

    /** Observable session (null when signed out). */
    public LiveData<Session> session() {
        return signedIn;
    }

    @WorkerThread
    @NonNull
    public Session register(@NonNull String email, @NonNull String password, @NonNull String handle)
            throws AuthException {
        return start(api.register(new AuthDto.Register(email.trim(), password, handle.trim())));
    }

    @WorkerThread
    @NonNull
    public Session login(@NonNull String email, @NonNull String password) throws AuthException {
        return start(api.login(new AuthDto.Login(email.trim(), password)));
    }

    /** Signs out here at once; tells the server too, but doesn't wait on it succeeding. */
    @WorkerThread
    public void logout() {
        Session s = session;
        clearLocal();
        if (s == null) return;
        try {
            api.logout(new AuthDto.Refresh(s.refreshToken)).execute();
        } catch (IOException ignored) {
            // Offline: the server-side session simply expires.
        }
    }

    /**
     * Returns a fresh access token after {@code staleAccessToken} stopped working (or is
     * about to). Single-flight: if another thread already refreshed, its token is returned
     * without a second call. Null if there is no usable session.
     */
    @WorkerThread
    @Nullable
    public synchronized String refreshAfter(@NonNull String staleAccessToken) {
        Session s = session;
        if (s == null) return null;
        if (!s.accessToken.equals(staleAccessToken)) return s.accessToken; // already refreshed
        try {
            Response<AuthDto.Tokens> r = api.refresh(new AuthDto.Refresh(s.refreshToken)).execute();
            AuthDto.Tokens body = r.body();
            if (r.isSuccessful() && body != null) return apply(body).accessToken;
            if (r.code() == 401) clearLocal(); // revoked or expired: signed out
            return null;
        } catch (IOException e) {
            return null; // offline: keep the session, this request fails
        }
    }

    /** True if the access token expires within {@code marginMillis}. */
    public boolean expiresWithin(@NonNull Session s, long marginMillis) {
        return clock.getAsLong() >= s.accessExpiresAtMillis - marginMillis;
    }

    @NonNull
    private Session start(@NonNull Call<AuthDto.Tokens> call) throws AuthException {
        Response<AuthDto.Tokens> r;
        try {
            r = call.execute();
        } catch (IOException e) {
            throw new AuthException(0, "Can't reach the server. Check your connection.");
        }
        AuthDto.Tokens body = r.body();
        if (r.isSuccessful() && body != null && body.user != null) return apply(body);
        throw new AuthException(r.code(), errorMessage(r.errorBody()));
    }

    @NonNull
    private synchronized Session apply(@NonNull AuthDto.Tokens t) {
        Session s = new Session(t.user.id, t.user.handle, t.accessToken, t.refreshToken,
                clock.getAsLong() + t.expiresIn * 1000L);
        store.save(s);
        session = s;
        signedIn.postValue(s);
        return s;
    }

    private synchronized void clearLocal() {
        session = null;
        store.clear();
        signedIn.postValue(null);
    }

    @NonNull
    private String errorMessage(@Nullable ResponseBody body) {
        try {
            if (body != null) {
                AuthDto.Error e = gson.fromJson(body.charStream(), AuthDto.Error.class);
                if (e != null && e.detail != null && !e.detail.isEmpty()) {
                    return Character.toUpperCase(e.detail.charAt(0)) + e.detail.substring(1) + ".";
                }
            }
        } catch (RuntimeException ignored) {
            // Not our JSON: fall through.
        }
        return "Something went wrong. Try again.";
    }
}
