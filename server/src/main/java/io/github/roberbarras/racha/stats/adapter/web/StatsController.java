package io.github.roberbarras.racha.stats.adapter.web;

import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.github.roberbarras.racha.stats.application.StatsService;
import io.github.roberbarras.racha.stats.domain.PersonalStats;
import io.github.roberbarras.racha.stats.domain.StatsDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneId;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Stats")
public class StatsController {
    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/stats/me/details")
    @Operation(summary = "The web panel's breakdowns: sessions, best streak, by hour and weekday (in `zone`), categories, devices, titles, recent")
    public StatsDetails details(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "UTC") String zone) {
        return stats.details(Caller.of(jwt).userId(), zone(zone));
    }

    @GetMapping("/stats/me")
    @Operation(summary = "Personal statistics; `zone` (IANA, e.g. Europe/Madrid) decides what \"today\" is for the streak")
    public PersonalStats me(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "UTC") String zone) {
        return stats.me(Caller.of(jwt).userId(), zone(zone));
    }

    private static ZoneId zone(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown time zone " + zone);
        }
    }
}
