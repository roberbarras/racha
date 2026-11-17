package io.github.roberbarras.racha.admin.application;

import io.github.roberbarras.racha.admin.domain.AdminPorts;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The admin screen: the server's state, its users, backups, logs and mail. Callers are admins (checked by security). */
public final class AdminService {
    /** What the server tells about itself, beyond counts and metrics. */
    public record ServerInfo(
            String version, Instant startedAt, boolean registrationOpen, boolean mailConfigured, boolean publicUrlSet, boolean metricsTokenSet,
            String trustedProxies) {}

    public record Overview(
            ServerInfo server, Duration uptime, AdminPorts.Counts counts, Map<String, Double> metrics, String backupsLocation,
            List<AdminPorts.BackupFile> recentBackups, long backupsBytes, String logLocation) {}

    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private final AdminPorts.Queries queries;
    private final AdminPorts.Backups backups;
    private final AdminPorts.Logs logs;
    private final AdminPorts.Metrics metrics;
    private final IdentityPorts.Users users;
    private final IdentityPorts.Mailer mailer;
    private final ServerInfo server;
    private final Clock clock;

    public AdminService(
            AdminPorts.Queries queries, AdminPorts.Backups backups, AdminPorts.Logs logs, AdminPorts.Metrics metrics,
            IdentityPorts.Users users, IdentityPorts.Mailer mailer, ServerInfo server, Clock clock) {
        this.queries = queries;
        this.backups = backups;
        this.logs = logs;
        this.metrics = metrics;
        this.users = users;
        this.mailer = mailer;
        this.server = server;
        this.clock = clock;
    }

    public Overview overview() {
        var now = clock.instant();
        var files = backups.list();
        return new Overview(server, Duration.between(server.startedAt(), now), queries.counts(now), metrics.snapshot(), backups.location(),
                files.stream().limit(5).toList(), files.stream().mapToLong(AdminPorts.BackupFile::bytes).sum(), logs.location());
    }

    /** The last 30 days of activity across the server. */
    public AdminPorts.Activity activity() {
        return queries.activity(clock.instant().minus(Duration.ofDays(30)));
    }

    public List<AdminPorts.UserSummary> users() {
        return queries.users(clock.instant());
    }

    public void changeRole(UUID actor, UUID target, String role) {
        if (!"admin".equals(role) && !"user".equals(role)) throw new AdminErrors.InvalidInput("role must be admin or user");
        var user = find(target);
        if ("user".equals(role) && user.isAdmin() && activeAdmins() <= 1) throw new AdminErrors.LastAdmin();
        queries.setRole(target, role, clock.instant());
    }

    public void setDisabled(UUID actor, UUID target, boolean disabled) {
        var user = find(target);
        if (disabled && actor.equals(target)) throw new AdminErrors.LastAdmin(); // never lock yourself out
        if (disabled && user.isAdmin() && activeAdmins() <= 1) throw new AdminErrors.LastAdmin();
        queries.setDisabled(target, disabled, clock.instant());
    }

    public List<AdminPorts.BackupFile> backups() {
        return backups.list();
    }

    public List<AdminPorts.LogEntry> logs(int max, String minLevel) {
        var level = minLevel == null ? "INFO" : minLevel.toUpperCase(java.util.Locale.ROOT);
        if (!LEVELS.contains(level)) throw new AdminErrors.InvalidInput("level must be one of " + LEVELS);
        return logs.tail(Math.clamp(max, 1, 2000), level);
    }

    /** Sends a message to the admin's own address now, so a wrong setting shows its error right here. */
    public String sendTestMail(UUID actor) {
        if (!mailer.configured()) throw new AdminErrors.MailFailed("mail is not configured (RACHA_MAIL_HOST)");
        var to = find(actor).email().value();
        try {
            mailer.send(to, "Racha: correo de prueba / test e-mail",
                    "Si lees esto, el servidor de Racha puede enviar correos (recuperación de contraseña).\n\n"
                            + "If you can read this, the Racha server can send e-mail (password recovery).\n");
        } catch (RuntimeException e) {
            throw new AdminErrors.MailFailed("the mail server refused it: " + rootMessage(e));
        }
        return to;
    }

    private long activeAdmins() {
        return queries.users(clock.instant()).stream().filter(u -> "admin".equals(u.role()) && !u.disabled()).count();
    }

    private io.github.roberbarras.racha.identity.domain.User find(UUID id) {
        return users.byId(id).orElseThrow(AdminErrors.UnknownUser::new);
    }

    private static String rootMessage(Throwable e) {
        var root = e;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
