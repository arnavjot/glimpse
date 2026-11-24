package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Accounts, sign-in, token refresh and rotation, sign-out, brute-force limits. */
class AuthApiTest extends IntegrationTest {

    private ResponseEntity<Map> post(String path, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(json, h), Map.class);
    }

    private ResponseEntity<Map> register(String email, String handle) {
        return post("/v1/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"correct horse\",\"handle\":\"" + handle + "\"}");
    }

    private ResponseEntity<Map> login(String email, String password) {
        return post("/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private ResponseEntity<Map> refresh(String token) {
        return post("/v1/auth/refresh", "{\"refresh_token\":\"" + token + "\"}");
    }

    private ResponseEntity<Map> me(String accessToken) {
        HttpHeaders h = new HttpHeaders();
        if (accessToken != null) h.setBearerAuth(accessToken);
        return http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @Test
    void registeringSignsYouIn() {
        ResponseEntity<Map> r = register("Arnav@Example.com", "arnav");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsKeys("access_token", "refresh_token").containsEntry("expires_in", 900);
        @SuppressWarnings("unchecked")
        Map<String, Object> me = me((String) r.getBody().get("access_token")).getBody();
        assertThat(me).containsEntry("handle", "arnav").containsEntry("email", "arnav@example.com");
    }

    @Test
    void anEmailOrHandleCanOnlyBeUsedOnce() {
        register("a@example.com", "first");
        assertThat(register("A@EXAMPLE.COM", "second").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> handleTaken = register("b@example.com", "FIRST");
        assertThat(handleTaken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handleTaken.getBody()).containsEntry("detail", "that handle is taken");
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() {
        register("a@example.com", "arnav");
        ResponseEntity<Map> wrongPassword = login("a@example.com", "nope nope");
        ResponseEntity<Map> unknownEmail = login("ghost@example.com", "nope nope");
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
        assertThat(login("A@example.com", "correct horse").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refreshingRotatesTheToken() {
        String first = (String) register("a@example.com", "arnav").getBody().get("refresh_token");
        ResponseEntity<Map> r = refresh(first);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = (String) r.getBody().get("refresh_token");
        assertThat(second).isNotEqualTo(first);
        assertThat(me((String) r.getBody().get("access_token")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reusingAnOldRefreshTokenEndsEverySession() {
        String first = (String) register("a@example.com", "arnav").getBody().get("refresh_token");
        String second = (String) refresh(first).getBody().get("refresh_token");
        // A minute later (past the retry grace), an attacker replays the first token:
        // refused, and the real user's session ends too.
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() - interval '1 minute' WHERE revoked_at IS NOT NULL");
        assertThat(refresh(first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRetryAfterALostResponseIsNotTreatedAsTheft() {
        String first = (String) register("a@example.com", "arnav").getBody().get("refresh_token");
        refresh(first); // the server rotated, but the response never reached the phone
        ResponseEntity<Map> retry = refresh(first);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me((String) retry.getBody().get("access_token")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh((String) retry.getBody().get("refresh_token")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void replayingATokenWhoseReplacementWasUsedIsStillTheft() {
        String first = (String) register("a@example.com", "arnav").getBody().get("refresh_token");
        String second = (String) refresh(first).getBody().get("refresh_token");
        String third = (String) refresh(second).getBody().get("refresh_token"); // the phone moved on
        // Within the grace period, but "second" was used: this can't be a lost response.
        assertThat(refresh(first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(third).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // every session ended
    }

    @Test
    void signingOutEndsTheSession() {
        String token = (String) register("a@example.com", "arnav").getBody().get("refresh_token");
        assertThat(post("/v1/auth/logout", "{\"refresh_token\":\"" + token + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpointsNeedAValidAccessToken() {
        assertThat(me(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me("not.a.jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String token = (String) register("a@example.com", "arnav").getBody().get("access_token");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(me(tampered).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void repeatedFailedLoginsAreThrottled() {
        register("a@example.com", "arnav");
        for (int i = 0; i < 10; i++) login("a@example.com", "wrong pass " + i);
        assertThat(login("a@example.com", "correct horse").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void weakOrMalformedInputIsRejected() {
        assertThat(post("/v1/auth/register", "{\"email\":\"a@example.com\",\"password\":\"short\",\"handle\":\"arnav\"}")
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/v1/auth/register", "{\"email\":\"not-an-email\",\"password\":\"long enough\",\"handle\":\"arnav\"}")
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/v1/auth/register", "{\"email\":\"a@example.com\",\"password\":\"long enough\",\"handle\":\"a b\"}")
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
