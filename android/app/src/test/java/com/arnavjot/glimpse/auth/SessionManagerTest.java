package com.arnavjot.glimpse.auth;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.arnavjot.glimpse.network.AuthApi;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Sign-in, persistence, and keeping the access token fresh (interceptor + authenticator). */
public class SessionManagerTest {

    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private static String tokens(String access, String refresh, long expiresIn) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"expires_in\":"
                + expiresIn + ",\"user\":{\"id\":\"u1\",\"handle\":\"arnav\"}}";
    }

    /** In-memory stand-in for the Keystore-encrypted store. */
    private static final class MemoryStore implements SessionStore {
        Session saved;

        @Override
        public Session load() {
            return saved;
        }

        @Override
        public void save(Session session) {
            saved = session;
        }

        @Override
        public void clear() {
            saved = null;
        }
    }

    private MockWebServer server;
    private final MemoryStore store = new MemoryStore();
    private long now = 1_000_000;
    private SessionManager sessions;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        OkHttpClient plain = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
        AuthApi api = new Retrofit.Builder().baseUrl(server.url("/")).client(plain)
                .addConverterFactory(GsonConverterFactory.create()).build().create(AuthApi.class);
        sessions = new SessionManager(api, store, () -> now);
        AuthInterceptor auth = new AuthInterceptor(sessions);
        client = plain.newBuilder().addInterceptor(auth).authenticator(auth).build();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private Response get(String path) throws Exception {
        return client.newCall(new Request.Builder().url(server.url(path)).build()).execute();
    }

    private void signIn(long expiresIn) throws Exception {
        server.enqueue(new MockResponse().setBody(tokens("access-1", "refresh-1", expiresIn)));
        sessions.login("a@example.com", "correct horse");
        server.takeRequest();
    }

    @Test
    public void signingInKeepsTheSessionAcrossRestarts() throws Exception {
        signIn(900);
        assertThat(sessions.current().handle).isEqualTo("arnav");
        SessionManager afterRestart = new SessionManager(null, store, () -> now);
        assertThat(afterRestart.current().refreshToken).isEqualTo("refresh-1");
    }

    @Test
    public void theServersMessageIsShownForABadSignIn() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"detail\":\"wrong email or password\"}"));
        SessionManager.AuthException e = assertThrows(SessionManager.AuthException.class,
                () -> sessions.login("a@example.com", "nope nope"));
        assertThat(e.httpCode).isEqualTo(401);
        assertThat(e.getMessage()).isEqualTo("Wrong email or password.");
        assertThat(sessions.current()).isNull();
    }

    @Test
    public void noNetworkIsReportedAsSuch() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        SessionManager.AuthException e = assertThrows(SessionManager.AuthException.class,
                () -> sessions.login("a@example.com", "correct horse"));
        assertThat(e.httpCode).isEqualTo(0);
    }

    @Test
    public void requestsCarryTheAccessToken() throws Exception {
        signIn(900);
        server.enqueue(new MockResponse().setBody("{}"));
        get("/v1/me");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer access-1");
    }

    @Test
    public void aRejectedTokenIsRefreshedAndTheRequestRetriedOnce() throws Exception {
        signIn(900);
        server.enqueue(new MockResponse().setResponseCode(401));                       // GET /v1/me
        server.enqueue(new MockResponse().setBody(tokens("access-2", "refresh-2", 900))); // refresh
        server.enqueue(new MockResponse().setBody("{\"handle\":\"arnav\"}"));          // retried GET
        Response r = get("/v1/me");
        assertThat(r.code()).isEqualTo(200);
        server.takeRequest();
        RecordedRequest refresh = server.takeRequest();
        assertThat(refresh.getPath()).isEqualTo("/v1/auth/refresh");
        assertThat(refresh.getBody().readUtf8()).contains("refresh-1");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer access-2");
        assertThat(store.saved.refreshToken).isEqualTo("refresh-2"); // the rotated token is kept
    }

    @Test
    public void aRefusedRefreshSignsOut() throws Exception {
        signIn(900);
        server.enqueue(new MockResponse().setResponseCode(401)); // GET
        server.enqueue(new MockResponse().setResponseCode(401)); // refresh refused (revoked)
        assertThat(get("/v1/me").code()).isEqualTo(401);
        assertThat(sessions.current()).isNull();
        assertThat(store.saved).isNull();
    }

    @Test
    public void anOfflineRefreshKeepsTheSession() throws Exception {
        signIn(900);
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThat(get("/v1/me").code()).isEqualTo(401);
        assertThat(sessions.current()).isNotNull(); // try again later; don't sign out
    }

    @Test
    public void aTokenAboutToExpireIsRefreshedBeforeTheRequest() throws Exception {
        signIn(10); // expires in 10 s: inside the 30 s early-refresh margin
        server.enqueue(new MockResponse().setBody(tokens("access-2", "refresh-2", 900)));
        server.enqueue(new MockResponse().setBody("{}"));
        get("/v1/me");
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/auth/refresh");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer access-2");
    }

    @Test
    public void concurrentRefreshesMakeOneCall() throws Exception {
        signIn(900);
        server.enqueue(new MockResponse().setBody(tokens("access-2", "refresh-2", 900)));
        // Two requests found "access-1" rejected: the second must reuse the first's result,
        // since the server treats a second use of refresh-1 as theft.
        assertThat(sessions.refreshAfter("access-1")).isEqualTo("access-2");
        assertThat(sessions.refreshAfter("access-1")).isEqualTo("access-2");
        assertThat(server.getRequestCount()).isEqualTo(2); // sign-in + one refresh
    }

    @Test
    public void signingOutClearsTheSessionEvenOffline() throws Exception {
        signIn(900);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        sessions.logout();
        assertThat(sessions.current()).isNull();
        assertThat(store.saved).isNull();
    }

    @Test
    public void requestsAreUntouchedWhenSignedOut() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThat(get("/v1/me").code()).isEqualTo(401);
        assertThat(server.takeRequest().getHeader("Authorization")).isNull();
        assertThat(server.getRequestCount()).isEqualTo(1); // no refresh attempted
    }
}
