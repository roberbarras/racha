package io.github.roberbarras.racha.admin.adapter.web;

import io.github.roberbarras.racha.admin.application.AdminService;
import io.github.roberbarras.racha.admin.domain.AdminPorts;
import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Server administration (admins only: enforced by the security configuration for every `/admin` path). */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Server state, users, backups, logs and mail; admins only")
public class AdminController {
    public record RoleRequest(String role) {}

    public record DisabledRequest(boolean disabled) {}

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/overview")
    @Operation(summary = "Version, uptime, configuration status, counts, runtime metrics, latest backups")
    public AdminService.Overview overview() {
        return admin.overview();
    }

    @GetMapping("/activity")
    @Operation(summary = "Last 30 days across the server: active users per day, watch hours per user, features, errors, sync failures")
    public AdminPorts.Activity activity() {
        return admin.activity();
    }

    @GetMapping("/users")
    @Operation(summary = "Every account with its role, devices, sessions and last activity")
    public List<AdminPorts.UserSummary> users() {
        return admin.users();
    }

    @PostMapping("/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Make an account admin or user (the last active admin cannot stop being one)")
    public void role(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody RoleRequest body) {
        admin.changeRole(Caller.of(jwt).userId(), id, body.role());
    }

    @PostMapping("/users/{id}/disabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disable (signs every device out) or enable an account; never your own")
    public void disabled(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody DisabledRequest body) {
        admin.setDisabled(Caller.of(jwt).userId(), id, body.disabled());
    }

    @GetMapping("/backups")
    @Operation(summary = "The backup service's dumps, newest first")
    public List<AdminPorts.BackupFile> backups() {
        return admin.backups();
    }

    @GetMapping("/logs")
    @Operation(summary = "The newest log events at this level or above (TRACE, DEBUG, INFO, WARN, ERROR)")
    public List<AdminPorts.LogEntry> logs(@RequestParam(defaultValue = "200") int lines, @RequestParam(defaultValue = "INFO") String level) {
        return admin.logs(lines, level);
    }

    @PostMapping("/mail/test")
    @Operation(summary = "Send a test e-mail to your own address now (the error, if the mail server refuses it)")
    public Map<String, String> testMail(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("sentTo", admin.sendTestMail(Caller.of(jwt).userId()));
    }
}
