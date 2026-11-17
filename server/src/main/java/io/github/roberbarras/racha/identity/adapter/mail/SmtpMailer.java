package io.github.roberbarras.racha.identity.adapter.mail;

import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Mail through the SMTP server of `RACHA_MAIL_*`; without a host it is {@link #disabled()}. */
public final class SmtpMailer implements IdentityPorts.Mailer {
    private final JavaMailSender sender;
    private final String from;
    private final String fromName;

    public SmtpMailer(JavaMailSender sender, String from, String fromName) {
        this.sender = sender;
        this.from = from;
        this.fromName = fromName;
    }

    public static IdentityPorts.Mailer disabled() {
        return new IdentityPorts.Mailer() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public void send(String to, String subject, String body) {
                throw new IllegalStateException("mail is not configured (RACHA_MAIL_HOST)");
            }
        };
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void send(String to, String subject, String body) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (Exception e) {
            throw new IllegalStateException("could not build the message", e);
        }
        sender.send(message);
    }
}
