package io.github.roberbarras.racha.identity.adapter.web;

import io.github.roberbarras.racha.identity.application.AuthErrors;
import io.github.roberbarras.racha.identity.application.PasswordService;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own account: who they are and changing their password. */
@RestController
@RequestMapping("/account")
@Tag(name = "Account")
public class AccountController {
    public record MeResponse(UUID userId, String email, String displayName, String role, Instant createdAt) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    private final PasswordService passwords;
    private final IdentityPorts.Users users;

    public AccountController(PasswordService passwords, IdentityPorts.Users users) {
        this.passwords = passwords;
        this.users = users;
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in account (role: user or admin)")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        var user = users.byId(Caller.of(jwt).userId()).filter(u -> !u.disabled()).orElseThrow(AuthErrors.InvalidRefreshToken::new);
        return new MeResponse(user.id(), user.email().value(), user.displayName(), user.role().wire(), user.createdAt());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the password (403 wrong_password); the account's other devices are signed out")
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @RequestBody ChangePasswordRequest body) {
        var caller = Caller.of(jwt);
        passwords.change(caller.userId(), caller.deviceId(), body.currentPassword(), body.newPassword());
    }
}
