package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** /.well-known/assetlinks.json once signing fingerprints are configured. */
@TestPropertySource(properties = "glimpse.app.sha256-fingerprints=AA:BB:CC, DD:EE:FF")
class AssetLinksApiTest extends IntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void declaresTheAppForAppLinks() {
        List<Map<String, Object>> statements = http.getForObject("/.well-known/assetlinks.json", List.class);
        assertThat(statements).hasSize(1);
        assertThat((List<String>) statements.get(0).get("relation"))
                .containsExactly("delegate_permission/common.handle_all_urls");
        Map<String, Object> target = (Map<String, Object>) statements.get(0).get("target");
        assertThat(target).containsEntry("namespace", "android_app")
                .containsEntry("package_name", "com.arnavjot.glimpse");
        assertThat((List<String>) target.get("sha256_cert_fingerprints")).containsExactly("AA:BB:CC", "DD:EE:FF");
    }
}
