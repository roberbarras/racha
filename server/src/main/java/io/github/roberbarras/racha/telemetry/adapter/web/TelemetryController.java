package io.github.roberbarras.racha.telemetry.adapter.web;

import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.github.roberbarras.racha.telemetry.application.TelemetryService;
import io.github.roberbarras.racha.telemetry.domain.Telemetry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** §7.3 `/analytics/events` and `/errors` (batches; resending a batch is harmless). */
@RestController
@Tag(name = "Telemetry")
public class TelemetryController {
    private final TelemetryService telemetry;

    public TelemetryController(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @PostMapping("/analytics/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Store usage events (playback, PiP, A-B loop, bookmarks…)")
    public TelemetryService.Accepted events(@AuthenticationPrincipal Jwt jwt, @RequestBody List<Telemetry.Event> events) {
        var caller = Caller.of(jwt);
        return telemetry.events(caller.userId(), caller.deviceId(), events);
    }

    @PostMapping("/errors")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Store client errors (message, stack trace, platform, version)")
    public TelemetryService.Accepted errors(@AuthenticationPrincipal Jwt jwt, @RequestBody List<Telemetry.ClientError> errors) {
        var caller = Caller.of(jwt);
        return telemetry.errors(caller.userId(), caller.deviceId(), errors);
    }
}
