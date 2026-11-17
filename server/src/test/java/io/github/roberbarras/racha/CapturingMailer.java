package io.github.roberbarras.racha;

import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A mail server for tests: keeps what would have been sent. */
@TestConfiguration(proxyBeanMethods = false)
public class CapturingMailer {
    public record Sent(String to, String subject, String body) {}

    public static final List<Sent> SENT = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    IdentityPorts.Mailer capturingMailer() {
        return new IdentityPorts.Mailer() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public void send(String to, String subject, String body) {
                SENT.add(new Sent(to, subject, body));
            }
        };
    }

    /** The newest message to [to], waiting a little: recovery mails go out in the background. */
    public static Sent awaitTo(String to) {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            var found = SENT.reversed().stream().filter(m -> m.to().equals(to)).findFirst();
            if (found.isPresent()) return found.get();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no mail to " + to);
    }
}
