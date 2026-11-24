package com.arnavjot.glimpse.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The rollout bucket, without Spring or a database. */
class RemoteConfigServiceTest {

    @Test
    void aDeviceAlwaysLandsInTheSameBucket() {
        int b = RemoteConfigService.bucket("preload", "device-42");
        for (int i = 0; i < 10; i++) assertThat(RemoteConfigService.bucket("preload", "device-42")).isEqualTo(b);
        assertThat(b).isBetween(0, 99);
    }

    @Test
    void bucketsSpreadEvenly() {
        int[] counts = new int[10];
        for (int i = 0; i < 10_000; i++) counts[RemoteConfigService.bucket("preload", "device-" + i) / 10]++;
        for (int c : counts) assertThat(c).isBetween(850, 1150); // ~1000 per decile
    }

    @Test
    void eachFlagUsesItsOwnSliceOfDevices() {
        int same = 0;
        for (int i = 0; i < 1000; i++) {
            String d = "device-" + i;
            boolean a = RemoteConfigService.bucket("preload", d) < 10;
            boolean b = RemoteConfigService.bucket("share_cards", d) < 10;
            if (a && b) same++;
        }
        // Independent 10% rollouts overlap on ~1% of devices, not on the same 10%.
        assertThat(same).isLessThan(40);
    }
}
