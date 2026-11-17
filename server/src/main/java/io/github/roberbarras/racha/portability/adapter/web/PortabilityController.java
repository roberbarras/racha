package io.github.roberbarras.racha.portability.adapter.web;

import io.github.roberbarras.racha.portability.application.PortabilityService;
import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.github.roberbarras.racha.sync.adapter.web.ChangeReader;
import io.github.roberbarras.racha.sync.adapter.web.SyncDtos;
import io.github.roberbarras.racha.telemetry.domain.Telemetry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A user's data as a file (`racha-backup`, ADR-0015), to keep it or take it to another server (H16). */
@RestController
@RequestMapping("/account")
@Tag(name = "Account")
public class PortabilityController {
    public static final String FORMAT = "racha-backup";
    public static final int SCHEMA_VERSION = 1;

    public record EventDto(UUID id, String type, Instant occurredAt, Map<String, Object> properties) {}

    /**
     * The file: [records] in sync's change shape, [events] the viewing events (statistics). [device] is what an app's
     * own backup adds (folders, device settings); a server ignores it.
     */
    public record BackupFile(
            String format, Integer schemaVersion, Instant createdAt, Map<String, Object> source, List<SyncDtos.ChangeDto> records,
            List<EventDto> events, Map<String, Object> device) {}

    public record ImportResponse(int applied, int stale, int invalid, int events, int duplicateEvents, int rejectedEvents) {}

    private final PortabilityService portability;
    private final ChangeReader reader;
    private final Clock clock;

    public PortabilityController(PortabilityService portability, ChangeReader reader, Clock clock) {
        this.portability = portability;
        this.reader = reader;
        this.clock = clock;
    }

    @GetMapping("/export")
    @Operation(summary = "Download all your data (racha-backup JSON): library edits, progress, bookmarks, history, favourites, settings, events")
    public ResponseEntity<BackupFile> export(@AuthenticationPrincipal Jwt jwt) {
        var data = portability.export(Caller.of(jwt).userId());
        var records = data.records().stream()
                .map(r -> new SyncDtos.ChangeDto(r.entity().wire(), r.id(), r.updatedAt(), false, r.data()))
                .toList();
        var events = data.events().stream().map(e -> new EventDto(e.id(), e.type(), e.occurredAt(), e.properties())).toList();
        var source = new LinkedHashMap<String, Object>();
        source.put("kind", "server");
        var now = clock.instant();
        var file = new BackupFile(FORMAT, SCHEMA_VERSION, now, source, records, events, null);
        var name = "racha-" + LocalDate.ofInstant(now, ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).body(file);
    }

    @PostMapping("/import")
    @Operation(summary = "Import a racha-backup file into your account (newest wins, like sync; repeating it changes nothing)")
    public ImportResponse importData(@AuthenticationPrincipal Jwt jwt, @RequestBody BackupFile body) {
        if (body == null || !FORMAT.equals(body.format())) throw new IllegalArgumentException("not a Racha backup (format " + FORMAT + ")");
        if (body.schemaVersion() == null || body.schemaVersion() > SCHEMA_VERSION) {
            throw new IllegalArgumentException("made by a newer version of Racha (schemaVersion " + body.schemaVersion() + ")");
        }
        var caller = Caller.of(jwt);
        var read = reader.read(body.records() == null ? List.of() : body.records());
        var events = body.events() == null ? List.<Telemetry.Event>of()
                : body.events().stream().map(e -> new Telemetry.Event(e.id(), e.type(), e.occurredAt(), e.properties())).toList();
        var report = portability.importData(caller.userId(), caller.deviceId(), read.valid(), read.rejected().size(), events);
        return new ImportResponse(report.applied(), report.stale(), report.invalid(), report.events(), report.duplicateEvents(), report.rejectedEvents());
    }
}
