package io.github.roberbarras.racha.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.roberbarras.racha.shared.adapter.web.TrustedProxies;
import org.junit.jupiter.api.Test;

/** Which address is the client's, behind Cloudflare Tunnel, a local reverse proxy or Cloudflare's proxy. */
class TrustedProxiesTest {
    private final TrustedProxies local = TrustedProxies.parse("local");

    @Test
    void aDirectConnectionIsItsOwnAddressWhateverItsHeadersSay() {
        assertThat(local.clientOf("203.0.113.7", "198.51.100.1", "198.51.100.2")).isEqualTo("203.0.113.7");
    }

    @Test
    void aTunnelOrProxyOnThisMachineOrLanTellsTheClient() {
        // cloudflared or nginx on the host reaches the container through Docker's network.
        assertThat(local.clientOf("172.18.0.1", "203.0.113.7", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(local.clientOf("127.0.0.1", null, "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(local.clientOf("::1", "2001:db8::5", null)).isEqualTo("2001:db8::5");
        assertThat(local.clientOf("10.0.0.2", null, null)).isEqualTo("10.0.0.2");
    }

    @Test
    void forwardedForIsReadFromTheRightSoAForgedLeftPartIsIgnored() {
        // The client sent "1.2.3.4" itself; our proxy appended the address it really saw.
        assertThat(local.clientOf("127.0.0.1", null, "1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
        // Two trusted hops (Docker, then nginx) before the client.
        assertThat(local.clientOf("172.18.0.1", null, "203.0.113.7, 192.168.1.10")).isEqualTo("203.0.113.7");
        // Garbage stops the walk: the proxy's own address is used.
        assertThat(local.clientOf("127.0.0.1", null, "evil.example.com")).isEqualTo("127.0.0.1");
        assertThat(local.clientOf("127.0.0.1", "not-an-ip", null)).isEqualTo("127.0.0.1");
    }

    @Test
    void cloudflaresProxyIsTrustedOnlyWhenAskedFor() {
        var edge = "104.16.0.10";
        assertThat(local.clientOf(edge, "203.0.113.7", "203.0.113.7")).isEqualTo(edge);
        var withCloudflare = TrustedProxies.parse("local,cloudflare");
        assertThat(withCloudflare.clientOf(edge, "203.0.113.7", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(withCloudflare.clientOf("2606:4700::1", "2001:db8::9", null)).isEqualTo("2001:db8::9");
    }

    @Test
    void noneAndCustomBlocks() {
        assertThat(TrustedProxies.parse("none").clientOf("127.0.0.1", "203.0.113.7", null)).isEqualTo("127.0.0.1");
        var custom = TrustedProxies.parse("198.51.100.0/24");
        assertThat(custom.clientOf("198.51.100.20", null, "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(custom.clientOf("127.0.0.1", null, "203.0.113.7")).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> TrustedProxies.parse("proxy.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedProxies.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }
}
