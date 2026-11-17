package io.github.roberbarras.racha.shared.adapter.web;

import io.github.roberbarras.racha.admin.application.AdminErrors;
import io.github.roberbarras.racha.identity.application.AuthErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors as RFC 9457 problem details, with a stable `code` the clients can switch on. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(AuthErrors.class)
    ProblemDetail auth(AuthErrors error) {
        var status = switch (error) {
            case AuthErrors.InvalidCredentials e -> HttpStatus.UNAUTHORIZED;
            case AuthErrors.InvalidRefreshToken e -> HttpStatus.UNAUTHORIZED;
            case AuthErrors.EmailTaken e -> HttpStatus.CONFLICT;
            case AuthErrors.RegistrationClosed e -> HttpStatus.FORBIDDEN;
            case AuthErrors.TooManyAttempts e -> HttpStatus.TOO_MANY_REQUESTS;
            case AuthErrors.InvalidInput e -> HttpStatus.BAD_REQUEST;
            case AuthErrors.WrongPassword e -> HttpStatus.FORBIDDEN;
            case AuthErrors.InvalidRecoveryCode e -> HttpStatus.BAD_REQUEST;
            case AuthErrors.MailNotConfigured e -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, code(error), error.getMessage());
    }

    @ExceptionHandler(AdminErrors.class)
    ProblemDetail admin(AdminErrors error) {
        var status = switch (error) {
            case AdminErrors.UnknownUser e -> HttpStatus.NOT_FOUND;
            case AdminErrors.LastAdmin e -> HttpStatus.CONFLICT;
            case AdminErrors.MailFailed e -> HttpStatus.BAD_GATEWAY;
            case AdminErrors.InvalidInput e -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, code(error), error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException error) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_input", error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException error) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_json", "the request body is not valid JSON for this endpoint");
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private static String code(RuntimeException error) {
        // InvalidCredentials -> invalid_credentials
        return error.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
