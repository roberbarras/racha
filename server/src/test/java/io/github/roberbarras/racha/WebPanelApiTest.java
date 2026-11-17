package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** The web panel (H14): its cookie session, devices, detailed statistics, admin activity and the page itself. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "racha.auth.admin-email=chief@example.com")
@Import(TestcontainersConfiguration.class)
class WebPanelApiTest {
    private static final String PASSWORD = "correct horse battery";
    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";

    @Value("${local.server.port}")
    int port;
    Api api;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void client() {
        api = new Api(port);
    }

    private HttpResponse<String> web(String path, Object body, String cookies, boolean header) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .header("User-Agent", FIREFOX)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (cookies != null) builder.header("Cookie", cookies);
        if (header) builder.header("X-Racha-Web", "1");
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String value(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void theBrowserKeepsItsRefreshTokenInAnHttpOnlyCookie() throws Exception {
        var email = "w-" + UUID.randomUUID() + "@example.com";
        api.post("/auth/register", Map.of("email", email, "password", PASSWORD, "device", Api.device("Portátil")), null);

        var login = web("/auth/web/login", Map.of("email", email, "password", PASSWORD), null, false);
        assertThat(login.statusCode()).isEqualTo(200);
        var body = json.readTree(login.body());
        assertThat(body.has("refreshToken")).as("never in the body").isFalse();
        assertThat(body.get("role").asString()).isEqualTo("user");
        var refresh = cookie(login, "racha_refresh");
        assertThat(refresh).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/auth/web").doesNotContain("Secure");
        var device = value(cookie(login, "racha_web_device"));

        // The access token works on the API; the device is the browser.
        var access = body.get("accessToken").asString();
        var devices = api.get("/devices", access).body();
        var mine = java.util.stream.StreamSupport.stream(devices.spliterator(), false).filter(d -> d.get("current").asBoolean()).findFirst().orElseThrow();
        assertThat(mine.get("platform").asString()).isEqualTo("web");
        assertThat(mine.get("name").asString()).isEqualTo("Web · Firefox · Linux");

        assertThat(web("/auth/web/refresh", null, value(refresh), false).statusCode()).as("needs the header").isEqualTo(401);
        var renewed = web("/auth/web/refresh", null, value(refresh), true);
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(web("/auth/web/refresh", null, value(refresh), true).statusCode()).as("rotated: the old cookie is spent").isEqualTo(401);

        var logout = web("/auth/web/logout", null, value(cookie(renewed, "racha_refresh")), true);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(cookie(logout, "racha_refresh")).contains("Max-Age=0");

        // The same browser signing in again, even without its device cookie, is the same device.
        var again = web("/auth/web/login", Map.of("email", email, "password", PASSWORD), null, false);
        assertThat(value(cookie(again, "racha_web_device"))).isEqualTo(device);
    }

    @Test
    void aLostDeviceIsSignedOutOrForgotten() {
        var laptop = api.register("Portátil");
        var email = api.get("/account/me", laptop.access()).body().get("email").asString();
        var phone = api.login(email, PASSWORD, "Móvil perdido");

        var list = api.get("/devices", laptop.access()).body();
        assertThat(list.size()).isEqualTo(2);
        assertThat(api.post("/devices/" + phone.deviceId() + "/sign-out", null, laptop.access()).status()).isEqualTo(204);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", phone.refresh()), null).status()).isEqualTo(401);
        var after = api.get("/devices", laptop.access()).body();
        after.forEach(d -> assertThat(d.get("activeSessions").asInt()).isEqualTo(d.get("current").asBoolean() ? 1 : 0));

        assertThat(api.call("DELETE", "/devices/" + phone.deviceId(), null, laptop.access()).status()).isEqualTo(204);
        assertThat(api.get("/devices", laptop.access()).body().size()).isEqualTo(1);
        // Someone else's device is not found, never touched.
        var other = api.register("Ajeno");
        assertThat(api.call("DELETE", "/devices/" + other.deviceId(), null, laptop.access()).status()).isEqualTo(404);
        assertThat(api.post("/devices/" + other.deviceId() + "/sign-out", null, laptop.access()).status()).isEqualTo(404);
    }

    @Test
    void detailedStatisticsBreakWatchingDown() {
        var s = api.register("Portátil");
        var today = LocalDate.now(ZoneOffset.UTC);
        api.post("/sync/push", Map.of("changes", List.of(
                Api.change("category", "courses", "2026-11-15T10:00:00Z", Map.of("name", "Cursos")),
                Api.change("libraryItem", "aws", "2026-11-15T10:00:00Z", Map.of("categoryId", "courses", "title", "AWS", "titleOverride", "AWS SAA")),
                Api.change("element", "e1", "2026-11-15T10:00:00Z", Map.of("itemId", "aws", "title", "Intro", "durationSec", 600)),
                Api.change("element", "e2", "2026-11-15T10:00:00Z", Map.of("itemId", "aws", "title", "IAM", "durationSec", 600)),
                Api.change("progress", "e1", "2026-11-15T10:00:00Z", Map.of("positionSec", 600, "completed", true)),
                Api.change("history", "h1", "2026-11-15T10:00:00Z", Map.of("elementId", "e1", "watchedAt", today + "T21:00:00Z", "positionSec", 600)))),
                s.access());
        api.post("/analytics/events", List.of(watch(today + "T21:00:00Z", 600, 1.5, "es"), watch(today + "T06:30:00Z", 300, 1.0, "")), s.access());

        var d = api.get("/stats/me/details?zone=Europe/Madrid", s.access()).body();
        assertThat(d.get("sessions").get("count").asLong()).isEqualTo(2);
        assertThat(d.get("sessions").get("averageSeconds").asLong()).isEqualTo(450);
        assertThat(d.get("sessions").get("withSubtitles").asDouble()).isEqualTo(0.5);
        assertThat(d.get("byHour").findValuesAsString("hour")).isNotEmpty();
        assertThat(d.get("byHour").get(0).get("hour").asInt()).as("06:30 UTC is 07:30 in Madrid").isEqualTo(7);
        assertThat(d.get("byCategory").get(0).get("name").asString()).isEqualTo("Cursos");
        assertThat(d.get("byDevice").get(0).get("name").asString()).isEqualTo("Portátil");
        var title = d.get("titles").get(0);
        assertThat(title.get("title").asString()).as("the user's edit wins").isEqualTo("AWS SAA");
        assertThat(title.get("completed").asLong() + "/" + title.get("elements").asLong()).isEqualTo("1/2");
        assertThat(title.get("seconds").asLong()).isEqualTo(900);
        assertThat(d.get("recent").get(0).get("element").asString()).isEqualTo("Intro");
        assertThat(d.get("bestStreak").get("days").asInt()).isEqualTo(1);
        assertThat(api.get("/stats/me/details?zone=Mars/Olympus", s.access()).status()).isEqualTo(400);
    }

    @Test
    void adminsSeeTheServersActivity() {
        var chief = api.post("/auth/register", Map.of("email", "chief@example.com", "password", PASSWORD, "device", Api.device("x")), null);
        var token = chief.status() == 201 ? Api.session(chief.body()).access() : api.login("chief@example.com", PASSWORD, "x").access();
        var someone = api.register("x");
        api.post("/analytics/events", List.of(Map.of("id", UUID.randomUUID().toString(), "type", "feature", "occurredAt", LocalDate.now(ZoneOffset.UTC) + "T08:00:00Z",
                "properties", Map.of("name", "pip"))), someone.access());
        var activity = api.get("/admin/activity", token);
        assertThat(activity.status()).isEqualTo(200);
        assertThat(activity.body().get("features").findValuesAsString("feature")).contains("pip");
        assertThat(activity.body().has("activeUsersByDay")).isTrue();
        assertThat(api.get("/admin/activity", someone.access()).status()).isEqualTo(403);
    }

    @Test
    void thePanelIsServedWithAStrictContentSecurityPolicy() throws Exception {
        var root = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(root.statusCode()).isEqualTo(302);
        assertThat(root.headers().firstValue("Location").orElse("")).endsWith("web/");
        var page = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/web/")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("app.js");
        assertThat(page.headers().firstValue("Content-Security-Policy").orElse("")).contains("script-src 'self'").contains("frame-ancestors 'none'");
        var script = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/web/app.js")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(script.statusCode()).isEqualTo(200);
    }

    private static Map<String, Object> watch(String at, long seconds, double speed, String subtitles) {
        var p = new HashMap<String, Object>();
        p.put("elementId", "e1");
        p.put("itemId", "aws");
        p.put("categoryId", "courses");
        p.put("startedAt", at);
        p.put("endedAt", at);
        p.put("positionSec", seconds);
        p.put("completed", true);
        p.put("playbackSpeed", speed);
        p.put("subtitlesLang", subtitles);
        p.put("seconds", seconds);
        return Map.of("id", UUID.randomUUID().toString(), "type", "watch", "occurredAt", at, "properties", p);
    }
}
