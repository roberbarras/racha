package io.github.roberbarras.racha.identity.adapter.web;

import io.github.roberbarras.racha.identity.application.AuthService;
import io.github.roberbarras.racha.identity.application.AuthTokens;
import io.github.roberbarras.racha.identity.application.DeviceInfo;
import io.github.roberbarras.racha.identity.application.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §7.2: `/auth/register`, `/auth/login`, `/auth/refresh` and `/auth/logout` (OAuth callback deferred, ADR-0006), and
 * password recovery by e-mail.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Accounts and sessions: email + password, JWT access token, rotating refresh token")
public class AuthController {
    public record DeviceRequest(UUID id, String name, String platform, String appVersion) {
        DeviceInfo info() {
            return new DeviceInfo(id, name, platform, appVersion);
        }
    }

    public record RegisterRequest(String email, String password, String displayName, DeviceRequest device) {}

    public record LoginRequest(String email, String password, DeviceRequest device) {}

    public record RefreshRequest(String refreshToken) {}

    public record ForgotPasswordRequest(String email) {}

    public record ResetPasswordRequest(String email, String code, String newPassword) {}

    public record TokenResponse(UUID userId, UUID deviceId, String accessToken, String tokenType, long expiresIn, String refreshToken) {
        static TokenResponse of(AuthTokens t) {
            return new TokenResponse(t.userId(), t.deviceId(), t.accessToken(), "Bearer", t.expiresInSeconds(), t.refreshToken());
        }
    }

    private final AuthService auth;
    private final PasswordService passwords;

    public AuthController(AuthService auth, PasswordService passwords) {
        this.auth = auth;
        this.passwords = passwords;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account and sign this device in")
    public TokenResponse register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        return TokenResponse.of(auth.register(body.email(), body.password(), body.displayName(), device(body.device()), request.getRemoteAddr()));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign a device in with email and password")
    public TokenResponse login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return TokenResponse.of(auth.login(body.email(), body.password(), device(body.device()), request.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for new tokens (the old one stops working)")
    public TokenResponse refresh(@RequestBody RefreshRequest body) {
        return TokenResponse.of(auth.refresh(body.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End this device's session")
    public void logout(@RequestBody RefreshRequest body) {
        auth.logout(body.refreshToken());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "E-mail a recovery code (same answer whether the account exists or not; 503 if the server cannot send mail)")
    public void forgotPassword(@RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
        passwords.requestCode(body.email(), request.getHeader("Accept-Language"), request.getRemoteAddr());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password with the e-mailed code; every session of the account ends")
    public void resetPassword(@RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        passwords.reset(body.email(), body.code(), body.newPassword(), request.getRemoteAddr());
    }

    private static DeviceInfo device(DeviceRequest device) {
        return device == null ? null : device.info();
    }
}
