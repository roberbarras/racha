package io.github.roberbarras.racha.config;

import io.github.roberbarras.racha.stats.adapter.persistence.JdbcStatsDetails;
import io.github.roberbarras.racha.stats.adapter.persistence.JdbcStatsQueries;
import io.github.roberbarras.racha.stats.application.StatsService;
import io.github.roberbarras.racha.sync.adapter.persistence.JdbcSyncStore;
import io.github.roberbarras.racha.sync.adapter.web.ChangeReader;
import io.github.roberbarras.racha.sync.adapter.web.Collections;
import io.github.roberbarras.racha.sync.application.SyncService;
import io.github.roberbarras.racha.telemetry.adapter.persistence.JdbcTelemetryStore;
import io.github.roberbarras.racha.telemetry.application.TelemetryService;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Composition of the sync, telemetry and stats contexts, and the OpenAPI description. */
@Configuration(proxyBeanMethods = false)
public class ApiConfiguration {
    @Bean
    JdbcSyncStore syncStore(JdbcClient jdbc, JsonMapper json) {
        return new JdbcSyncStore(jdbc, json);
    }

    @Bean
    SyncService syncService(JdbcSyncStore store, Clock clock, RachaProperties properties) {
        return new SyncService(store, store, clock, properties.limits().maxBatch());
    }

    @Bean
    ChangeReader changeReader(JsonMapper json, RachaProperties properties) {
        return new ChangeReader(json, properties.limits().maxChangeBytes());
    }

    @Bean
    Collections collections(SyncService sync, ChangeReader reader) {
        return new Collections(sync, reader);
    }

    @Bean
    TelemetryService telemetryService(JdbcClient jdbc, JsonMapper json, Clock clock, RachaProperties properties) {
        return new TelemetryService(new JdbcTelemetryStore(jdbc, json), clock, properties.limits().maxBatch());
    }

    @Bean
    StatsService statsService(JdbcClient jdbc, Clock clock) {
        return new StatsService(new JdbcStatsQueries(jdbc), new JdbcStatsDetails(jdbc), clock);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Racha API").version("1").description(
                        "Accounts, sync and statistics for the Racha apps (spec §7). The server never stores or serves media."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
