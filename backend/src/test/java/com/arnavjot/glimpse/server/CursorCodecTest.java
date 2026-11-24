package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.arnavjot.glimpse.server.feed.CursorCodec;

/** Plain unit test: no Spring, no containers. */
class CursorCodecTest {

    @Test
    void roundTrips() {
        Instant t = Instant.parse("2026-11-21T10:15:30Z");
        CursorCodec.Position p = CursorCodec.decode(CursorCodec.encode(t, "v123"));
        assertThat(p.publishedAt()).isEqualTo(t);
        assertThat(p.id()).isEqualTo("v123");
    }

    @Test
    void isUrlSafe() {
        assertThat(CursorCodec.encode(Instant.parse("2026-11-21T10:15:30.999Z"), "v~?/+"))
                .doesNotContain("+", "/", "=");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> CursorCodec.decode("not-a-cursor")).isInstanceOf(CursorCodec.BadCursorException.class);
        assertThatThrownBy(() -> CursorCodec.decode("")).isInstanceOf(CursorCodec.BadCursorException.class);
    }
}
