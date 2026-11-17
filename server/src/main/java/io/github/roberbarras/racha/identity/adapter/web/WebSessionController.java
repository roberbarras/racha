package io.github.roberbarras.racha.identity.adapter.web;

import io.github.roberbarras.racha.identity.application.AuthErrors;
import io.github.roberbarras.racha.identity.application.AuthService;
import io.github.roberbarras.racha.identity.application.AuthTokens;
import io.github.roberbarras.racha.identity.application.DeviceInfo;
import io.github.roberbarras.racha.identity.domain.Device;
import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.identity.domain.Platform;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessions of the web panel (H14). The refresh token never reaches JavaScript: it lives in an HttpOnly, SameSite=Strict
 * cookie limited to these endpoints, and the page keeps only the short access token in memory. Refresh and logout also
 * need the `X-Racha-Web` header, which no other site can add without a preflight the server does not grant.
 */
@RestController
@RequestMapping("/auth/web")
@Tag(name = "Web session", description = "Sign-in for the web panel: refresh token in an HttpOnly cookie")
public class WebSessionController {
    static final String REFRESH_COOKIE = "racha_refresh";
    /** Not secret: lets the same browser be the same device on every sign-in. */
    static final String DEVICE_COOKIE = "racha_web_device";
    static final String HEADER = "X-Racha-Web";

    public record WebLoginRequest(String email, String password) {}

    public record WebSessionResponse(UUID userId, UUID deviceId, String accessToken, long expiresIn, String email, String role) {}

    private final AuthService auth;
    private final IdentityPorts.Users users;
    private final IdentityPorts.Devices devices;
    private final Duration refreshLifetime;

    public WebSessionController(
            AuthService auth, IdentityPorts.Users users, IdentityPorts.Devices devices, @Value("${racha.auth.refresh-token-days}") int refreshDays) {
        this.auth = auth;
        this.users = users;
        this.devices = devices;
        this.refreshLifetime = Duration.ofDays(refreshDays);
    }

    @PostMapping("/login")
    @Operation(summary = "Sign the browser in; sets the refresh cookie")
    public WebSessionResponse login(@RequestBody WebLoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var name = browserName(request.getHeader("User-Agent"));
        // Without the device cookie (cleared, another profile), the same browser on the same system is the same device.
        var device = cookie(request, DEVICE_COOKIE).flatMap(WebSessionController::uuid)
                .or(() -> sameBrowser(body.email(), name)).orElse(null);
        var tokens = auth.login(body.email(), body.password(), new DeviceInfo(device, name, "web", null), request.getRemoteAddr());
        return session(tokens, request, response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "A new access token from the refresh cookie (rotates it); needs the X-Racha-Web header")
    public WebSessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        requireHeader(request);
        var token = cookie(request, REFRESH_COOKIE).orElseThrow(AuthErrors.InvalidRefreshToken::new);
        try {
            return session(auth.refresh(token), request, response);
        } catch (AuthErrors.InvalidRefreshToken e) {
            clear(request, response);
            throw e;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign the browser out and clear its cookie; needs the X-Racha-Web header")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        requireHeader(request);
        cookie(request, REFRESH_COOKIE).ifPresent(auth::logout);
        clear(request, response);
    }

    private Optional<UUID> sameBrowser(String rawEmail, String name) {
        try {
            var user = users.byEmail(Email.of(rawEmail));
            return user.flatMap(u -> devices.ofUser(u.id()).stream()
                    .filter(d -> d.platform() == Platform.WEB && d.name().equals(name)).map(Device::id).findFirst());
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // not an e-mail: the login itself will say so
        }
    }

    private WebSessionResponse session(AuthTokens tokens, HttpServletRequest request, HttpServletResponse response) {
        var user = users.byId(tokens.userId()).orElseThrow(AuthErrors.InvalidRefreshToken::new);
        response.addHeader("Set-Cookie", cookie(request, REFRESH_COOKIE, tokens.refreshToken(), refreshLifetime, "/auth/web").toString());
        response.addHeader("Set-Cookie", cookie(request, DEVICE_COOKIE, tokens.deviceId().toString(), Duration.ofDays(3650), "/auth/web").toString());
        return new WebSessionResponse(tokens.userId(), tokens.deviceId(), tokens.accessToken(), tokens.expiresInSeconds(), user.email().value(),
                user.role().wire());
    }

    private static void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie(request, REFRESH_COOKIE, "", Duration.ZERO, "/auth/web").toString());
    }

    private static ResponseCookie cookie(HttpServletRequest request, String name, String value, Duration maxAge, String path) {
        // Secure when the browser reached us over HTTPS (directly or through a proxy that says so); on a plain-HTTP LAN
        // address a Secure cookie would be dropped and the panel could not stay signed in.
        var https = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        return ResponseCookie.from(name, value).httpOnly(true).secure(https).sameSite("Strict")
                .path(request.getContextPath() + path).maxAge(maxAge).build();
    }

    private static void requireHeader(HttpServletRequest request) {
        if (!"1".equals(request.getHeader(HEADER))) throw new AuthErrors.InvalidRefreshToken();
    }

    private static Optional<String> cookie(HttpServletRequest request, String name) {
        var cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies).filter(c -> c.getName().equals(name)).map(Cookie::getValue).filter(v -> !v.isBlank()).findFirst();
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** "Firefox · Linux", so the device list says which browser it is. */
    static String browserName(String userAgent) {
        var ua = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        var browser = ua.contains("edg/") ? "Edge" : ua.contains("firefox/") ? "Firefox" : ua.contains("chrome/") ? "Chrome"
                : ua.contains("safari/") ? "Safari" : "Navegador";
        var os = ua.contains("android") ? "Android" : ua.contains("iphone") || ua.contains("ipad") ? "iOS" : ua.contains("windows") ? "Windows"
                : ua.contains("mac os") ? "macOS" : ua.contains("linux") ? "Linux" : null;
        return "Web · " + browser + (os == null ? "" : " · " + os);
    }
}
