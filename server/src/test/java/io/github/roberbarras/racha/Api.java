package io.github.roberbarras.racha;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON-over-HTTP client for the API tests (real HTTP against the random port, like the apps). */
public final class Api {
    public record Response(int status, JsonNode body) {}

    public record Session(UUID userId, UUID deviceId, String access, String refresh) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base;

    public Api(int port) {
        this.base = "http://localhost:" + port;
    }

    public Response call(String method, String path, Object body, String token) {
        return call(method, path, body, token, null);
    }

    /** [from]: the client address a local proxy reports (X-Forwarded-For); the tests connect from loopback. */
    public Response call(String method, String path, Object body, String token, String from) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (from != null) builder.header("X-Forwarded-For", from);
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            var response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            var text = response.body();
            return new Response(response.statusCode(), text == null || text.isBlank() ? json.nullNode() : json.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Response get(String path, String token) {
        return call("GET", path, null, token);
    }

    public Response post(String path, Object body, String token) {
        return call("POST", path, body, token);
    }

    public static Map<String, Object> device(String name) {
        return Map.of("name", name, "platform", "linux", "appVersion", "0.0.1");
    }

    /** Registers a fresh user (unique email) and returns its first session. */
    public Session register(String deviceName) {
        var email = "user-" + UUID.randomUUID() + "@example.com";
        var r = post("/auth/register", Map.of("email", email, "password", "correct horse battery", "device", device(deviceName)), null);
        if (r.status() != 201) throw new IllegalStateException("register failed: " + r);
        return session(r.body());
    }

    public Session login(String email, String password, String deviceName) {
        var r = post("/auth/login", Map.of("email", email, "password", password, "device", device(deviceName)), null);
        if (r.status() != 200) throw new IllegalStateException("login failed: " + r);
        return session(r.body());
    }

    public static Session session(JsonNode body) {
        return new Session(UUID.fromString(body.get("userId").asString()), UUID.fromString(body.get("deviceId").asString()),
                body.get("accessToken").asString(), body.get("refreshToken").asString());
    }

    public static Map<String, Object> change(String entity, String id, String updatedAt, Map<String, Object> data) {
        return Map.of("entity", entity, "id", id, "updatedAt", updatedAt, "deleted", false, "data", data);
    }

    public static Map<String, Object> push(List<Map<String, Object>> changes) {
        return Map.of("changes", changes);
    }
}
