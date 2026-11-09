package dev.overlook.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostGuardTest {

    @Test
    void acceptsOnlyLoopbackNamesOnOurPort() {
        assertThat(HostGuard.allowed("127.0.0.1:7878", 7878)).isTrue();
        assertThat(HostGuard.allowed("LOCALHOST:7878", 7878)).isTrue();
        assertThat(HostGuard.allowed("localhost:7879", 7878)).isFalse();
        assertThat(HostGuard.allowed("attacker.com:7878", 7878)).isFalse();
        assertThat(HostGuard.allowed("localhost.attacker.com:7878", 7878)).isFalse();
        assertThat(HostGuard.allowed("[::1]:7878", 7878)).isFalse();
        assertThat(HostGuard.allowed("localhost", 7878)).isFalse();
        assertThat(HostGuard.allowed("", 7878)).isFalse();
        assertThat(HostGuard.allowed(null, 7878)).isFalse();
    }

    @Test
    void originMustBeLocalWhenPresent() {
        assertThat(HostGuard.allowedOrigin(null, 7878)).isTrue();
        assertThat(HostGuard.allowedOrigin("http://127.0.0.1:7878", 7878)).isTrue();
        assertThat(HostGuard.allowedOrigin("http://localhost:7878", 7878)).isTrue();
        assertThat(HostGuard.allowedOrigin("https://evil.com", 7878)).isFalse();
        assertThat(HostGuard.allowedOrigin("null", 7878)).isFalse();
    }
}
