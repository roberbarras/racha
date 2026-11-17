package io.github.roberbarras.racha.config;

import io.github.roberbarras.racha.admin.adapter.files.FileBackups;
import io.github.roberbarras.racha.admin.adapter.files.FileLogs;
import io.github.roberbarras.racha.admin.adapter.metrics.MicrometerMetrics;
import io.github.roberbarras.racha.admin.adapter.persistence.JdbcAdminQueries;
import io.github.roberbarras.racha.admin.application.AdminService;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Composition of the admin context. */
@Configuration(proxyBeanMethods = false)
public class AdminConfiguration {
    @Bean
    AdminService adminService(
            JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json, MeterRegistry registry, IdentityPorts.Users users,
            IdentityPorts.Mailer mailer, RachaProperties properties, ObjectProvider<BuildProperties> build, Clock clock) {
        var info = build.getIfAvailable();
        var server = new AdminService.ServerInfo(
                info == null ? "dev" : info.getVersion(),
                clock.instant(),
                properties.auth().registrationOpen(),
                mailer.configured(),
                properties.publicUrl() != null && !properties.publicUrl().isBlank(),
                properties.metricsToken() != null && !properties.metricsToken().isBlank(),
                properties.network() == null || properties.network().trustedProxies() == null ? "local" : properties.network().trustedProxies());
        return new AdminService(new JdbcAdminQueries(jdbc, transactions), new FileBackups(properties.backupDir()),
                new FileLogs(properties.logFile(), json), new MicrometerMetrics(registry), users, mailer, server, clock);
    }
}
