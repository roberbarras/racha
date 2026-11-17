package io.github.roberbarras.racha.identity.application;

import java.time.Duration;
import java.util.Locale;

/** The recovery e-mail, in Spanish or English after the app's language (`Accept-Language`). */
record RecoveryMail(String subject, String body) {
    static RecoveryMail of(String language, String code, String link, Duration validity) {
        var minutes = validity.toMinutes();
        if (language != null && language.toLowerCase(Locale.ROOT).startsWith("es")) {
            return new RecoveryMail("Racha: código para cambiar tu contraseña", """
                    Hola:

                    Alguien (seguramente tú) ha pedido cambiar la contraseña de tu cuenta de Racha.

                    Tu código: %s

                    Escríbelo en la app, en Cuenta → «¿Olvidaste tu contraseña?». Vale %d minutos y una sola vez.
                    %s
                    Si no lo has pedido tú, ignora este correo: tu contraseña no cambia.
                    """.formatted(code, minutes, link == null ? "" : "\nO abre este enlace:\n" + link + "\n"));
        }
        return new RecoveryMail("Racha: your code to reset your password", """
                Hi,

                Someone (most likely you) asked to reset the password of your Racha account.

                Your code: %s

                Type it in the app, in Account → "Forgot your password?". It works once, for %d minutes.
                %s
                If it was not you, ignore this e-mail: your password does not change.
                """.formatted(code, minutes, link == null ? "" : "\nOr open this link:\n" + link + "\n"));
    }
}
