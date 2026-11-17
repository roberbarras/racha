package io.github.roberbarras.racha.shared.adapter.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which proxies may tell us the client's address (`RACHA_TRUSTED_PROXIES`). Behind Cloudflare Tunnel, nginx or Caddy
 * every request comes from the proxy; without this, login throttling would see one address for everybody.
 *
 * <p>The setting is a comma-separated list of: {@code local} (loopback and private networks: a tunnel or reverse
 * proxy on the same machine or LAN, Docker's own network), {@code cloudflare} (Cloudflare's published ranges, for its
 * proxy pointing straight at this server), {@code none}, or CIDR blocks. The default is {@code local}.
 */
public final class TrustedProxies {
    /** https://www.cloudflare.com/ips/ (ranges change rarely; extra CIDRs can be added to the setting). */
    static final List<String> CLOUDFLARE = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
            "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32");

    static final List<String> LOCAL = List.of(
            "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10");

    private final List<Cidr> trusted;

    private TrustedProxies(List<Cidr> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    public static TrustedProxies parse(String setting) {
        var blocks = new ArrayList<Cidr>();
        var spec = setting == null || setting.isBlank() ? "local" : setting;
        for (var raw : spec.split(",")) {
            var part = raw.strip().toLowerCase(Locale.ROOT);
            switch (part) {
                case "", "none" -> { }
                case "local", "default" -> LOCAL.forEach(c -> blocks.add(Cidr.parse(c)));
                case "cloudflare" -> CLOUDFLARE.forEach(c -> blocks.add(Cidr.parse(c)));
                default -> blocks.add(Cidr.parse(part));
            }
        }
        return new TrustedProxies(blocks);
    }

    /**
     * The client's address: the connection's own unless it comes from a trusted proxy, then `CF-Connecting-IP`
     * (Cloudflare) or the nearest untrusted hop of `X-Forwarded-For` (read right to left: the left part can be forged).
     */
    public String clientOf(String remoteAddr, String cfConnectingIp, String forwardedFor) {
        if (!trusts(remoteAddr)) return remoteAddr;
        var cloudflare = literal(cfConnectingIp);
        if (cloudflare != null) return cloudflare;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            var hops = forwardedFor.split(",");
            String leftmost = null;
            for (int i = hops.length - 1; i >= 0; i--) {
                var hop = literal(hops[i]);
                if (hop == null) break; // garbage: stop trusting the rest
                leftmost = hop;
                if (!trusts(hop)) return hop;
            }
            if (leftmost != null) return leftmost;
        }
        return remoteAddr;
    }

    boolean trusts(String address) {
        var parsed = literal(address);
        if (parsed == null) return false;
        var bytes = toBytes(parsed);
        return bytes != null && trusted.stream().anyMatch(c -> c.contains(bytes));
    }

    /** An IP literal (never a host name: no DNS lookups from request headers), or null. */
    private static String literal(String value) {
        if (value == null) return null;
        var v = value.strip();
        if (v.startsWith("[") && v.contains("]")) v = v.substring(1, v.indexOf(']'));
        return toBytes(v) == null ? null : v;
    }

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    /** Bytes of an IPv4 dotted quad or an IPv6 literal; null for anything else, so no text ever reaches DNS. */
    private static byte[] toBytes(String literal) {
        var ipv4 = IPV4.matcher(literal);
        if (ipv4.matches()) {
            var bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int part = Integer.parseInt(ipv4.group(i + 1));
                if (part > 255) return null;
                bytes[i] = (byte) part;
            }
            return bytes;
        }
        if (!literal.contains(":") || !literal.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(literal).getAddress(); // with ':' Java parses an IPv6 literal, never looks it up
        } catch (UnknownHostException e) {
            return null;
        }
    }

    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String text) {
            var slash = text.indexOf('/');
            var address = toBytes(slash < 0 ? text : text.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("RACHA_TRUSTED_PROXIES: not an address or CIDR: " + text);
            }
            var prefix = slash < 0 ? address.length * 8 : Integer.parseInt(text.substring(slash + 1));
            if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException("RACHA_TRUSTED_PROXIES: bad prefix: " + text);
            return new Cidr(address, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int full = prefix / 8, rest = prefix % 8;
            for (int i = 0; i < full; i++) if (address[i] != network[i]) return false;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }
}
