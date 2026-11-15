package io.github.roberbarras.racha;

import static io.github.roberbarras.racha.Api.change;
import static io.github.roberbarras.racha.Api.push;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/** §6.3 / §7.3–7.4 / ADR-0012 against a real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SyncApiTest {
    private static final String T1 = "2026-11-15T10:00:00Z";
    private static final String T2 = "2026-11-15T11:00:00Z";
    private static final String T3 = "2026-11-15T12:00:00Z";

    @Value("${local.server.port}")
    int port;
    Api api;
    Api.Session phone;
    Api.Session laptop;

    @BeforeEach
    void users() {
        api = new Api(port);
        phone = api.register("Móvil");
        var email = api.get("/devices", phone.access()); // same user, second device:
        assertThat(email.status()).isEqualTo(200);
        laptop = sameUserOtherDevice();
    }

    /** Logs the phone's user in from a laptop (re-registers the email it was created with). */
    private Api.Session sameUserOtherDevice() {
        var refreshed = api.post("/auth/refresh", Map.of("refreshToken", phone.refresh()), null);
        phone = Api.session(refreshed.body());
        var device = api.post("/devices/register", Api.device("Portátil"), phone.access());
        assertThat(device.status()).isEqualTo(200);
        // A token bound to the new device: refresh tokens belong to a device, so log in through the use case.
        return new Api.Session(phone.userId(), java.util.UUID.fromString(device.body().get("id").asString()), phone.access(), phone.refresh());
    }

    private JsonNode pushOk(Api.Session s, List<Map<String, Object>> changes) {
        var r = api.post("/sync/push", push(changes), s.access());
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private JsonNode pull(Api.Session s, long since) {
        var r = api.get("/sync/pull?since=" + since, s.access());
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    @Test
    void criterion5PushedChangesReachTheOtherDeviceIncrementally() {
        var first = pushOk(phone, List.of(
                change("progress", "el-1", T1, Map.of("positionSec", 754, "durationSec", 2710, "completed", false)),
                change("bookmark", "bm-1", T1, Map.of("elementId", "el-1", "positionSec", 120, "note", "IAM roles"))));
        assertThat(first.get("applied").asInt()).isEqualTo(2);

        var all = pull(laptop, 0);
        assertThat(all.get("changes")).hasSize(2);
        assertThat(all.get("hasMore").asBoolean()).isFalse();
        var progress = all.get("changes").get(0);
        assertThat(progress.get("entity").asString()).isEqualTo("progress");
        assertThat(progress.get("data").get("positionSec").asLong()).isEqualTo(754);
        long cursor = all.get("cursor").asLong();

        pushOk(phone, List.of(change("favorite", "item:aws", T2, Map.of("itemId", "aws"))));
        var next = pull(laptop, cursor);
        assertThat(next.get("changes")).hasSize(1);
        assertThat(next.get("changes").get(0).get("id").asString()).isEqualTo("item:aws");
        assertThat(pull(laptop, next.get("cursor").asLong()).get("changes")).isEmpty();
    }

    @Test
    void lastWriterWinsByUpdatedAt() {
        pushOk(phone, List.of(change("category", "courses", T2, Map.of("name", "Cursos", "color", "#E8812A"))));
        var older = pushOk(laptop, List.of(change("category", "courses", T1, Map.of("name", "Formación"))));
        var result = older.get("results").get(0);
        assertThat(result.get("outcome").asString()).isEqualTo("stale");
        assertThat(result.get("server").get("data").get("name").asString()).isEqualTo("Cursos");

        var newer = pushOk(laptop, List.of(change("category", "courses", T3, Map.of("name", "Formación"))));
        assertThat(newer.get("results").get(0).get("outcome").asString()).isEqualTo("applied");
        assertThat(api.get("/categories/courses", phone.access()).body().get("data").get("name").asString()).isEqualTo("Formación");

        // The same change sent twice (a retried push) is not a conflict.
        var retried = pushOk(laptop, List.of(change("category", "courses", T3, Map.of("name", "Formación"))));
        assertThat(retried.get("results").get(0).get("outcome").asString()).isEqualTo("applied");
    }

    @Test
    void progressIsNeverLost() {
        pushOk(phone, List.of(change("progress", "el-9", T1, Map.of("positionSec", 300, "completed", false))));
        // A newer change rewinds (the user started over): it wins, but the furthest position is kept aside.
        pushOk(laptop, List.of(change("progress", "el-9", T2, Map.of("positionSec", 100, "completed", false))));
        var stored = api.get("/progress/el-9", phone.access()).body();
        assertThat(stored.get("data").get("positionSec").asLong()).isEqualTo(100);
        assertThat(stored.get("data").get("maxPositionSec").asLong()).isEqualTo(300);

        // An older change that went further loses, yet still raises the furthest position.
        var stale = pushOk(phone, List.of(change("progress", "el-9", T1, Map.of("positionSec", 500, "completed", false))));
        assertThat(stale.get("results").get(0).get("outcome").asString()).isEqualTo("stale");
        assertThat(stale.get("results").get(0).get("server").get("data").get("maxPositionSec").asLong()).isEqualTo(500);
    }

    @Test
    void historyIsAppendOnly() {
        var entry = change("history", "h-1", T1, Map.of("elementId", "el-1", "watchedAt", T1, "positionSec", 60));
        pushOk(phone, List.of(entry));
        var again = pushOk(laptop, List.of(change("history", "h-1", T3, Map.of("elementId", "el-2", "watchedAt", T3, "positionSec", 1))));
        assertThat(again.get("results").get(0).get("outcome").asString()).isEqualTo("applied");
        var history = api.get("/history", phone.access()).body().get("changes");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("data").get("elementId").asString()).isEqualTo("el-1");
    }

    @Test
    void aLosingSettingsChangeComesBackSoTheAppCanAsk() {
        var put = api.call("PUT", "/settings", List.of(Map.of("id", "user:theme", "updatedAt", T2, "data", Map.of("value", "dark"))), phone.access());
        assertThat(put.body().get("applied").asInt()).isEqualTo(1);
        var conflict = api.call("PUT", "/settings", List.of(Map.of("id", "user:theme", "updatedAt", T1, "data", Map.of("value", "light"))), laptop.access());
        var result = conflict.body().get("results").get(0);
        assertThat(result.get("outcome").asString()).isEqualTo("stale");
        assertThat(result.get("server").get("data").get("value").asString()).isEqualTo("dark");

        // Per-device settings are only listed for their device.
        api.call("PUT", "/settings", List.of(Map.of("id", "device:" + phone.deviceId() + ":zoom", "updatedAt", T1, "data", Map.of("value", "fill"))), phone.access());
        var phoneView = ids(api.get("/settings", phone.access()).body());
        assertThat(phoneView).containsExactlyInAnyOrder("user:theme", "device:" + phone.deviceId() + ":zoom");
        assertThat(api.call("PUT", "/settings", List.of(Map.of("id", "theme", "updatedAt", T1, "data", Map.of("value", "x"))), phone.access())
                .body().get("results").get(0).get("outcome").asString()).isEqualTo("invalid");
    }

    @Test
    void deletionsTravelAsTombstones() {
        api.call("PUT", "/categories/hobbies", Map.of("updatedAt", T1, "data", Map.of("name", "Hobbies")), phone.access());
        long cursor = pull(laptop, 0).get("cursor").asLong();
        assertThat(api.call("DELETE", "/categories/hobbies", null, phone.access()).status()).isEqualTo(200);
        assertThat(api.get("/categories/hobbies", phone.access()).status()).isEqualTo(404);
        var tombstone = pull(laptop, cursor).get("changes").get(0);
        assertThat(tombstone.get("id").asString()).isEqualTo("hobbies");
        assertThat(tombstone.get("deleted").asBoolean()).isTrue();
    }

    @Test
    void invalidChangesAreReportedNotStored() {
        var r = pushOk(phone, List.of(
                change("progress", "el-x", T1, Map.of("completed", true)), // positionSec missing
                change("spaceship", "s-1", T1, Map.of()),
                change("bookmark", "bm-x", T1, Map.of("note", "no element")),
                change("history", "h-x", T1, Map.of("elementId", "el", "watchedAt", "yesterday"))));
        assertThat(r.get("invalid").asInt()).isEqualTo(4);
        assertThat(pull(phone, 0).get("changes")).isEmpty();
    }

    @Test
    void aClockAheadCannotWinForever() {
        var future = Instant.now().plus(30, ChronoUnit.DAYS).toString();
        pushOk(phone, List.of(change("category", "series", future, Map.of("name", "Series del futuro"))));
        var stored = api.get("/categories/series", phone.access()).body();
        assertThat(Instant.parse(stored.get("updatedAt").asString())).isBefore(Instant.now().plus(1, ChronoUnit.MINUTES));
        // So a normal change made a bit later still wins.
        pushOk(laptop, List.of(change("category", "series", Instant.now().plus(1, ChronoUnit.MINUTES).toString(), Map.of("name", "Series"))));
        assertThat(api.get("/categories/series", phone.access()).body().get("data").get("name").asString()).isEqualTo("Series");
    }

    @Test
    void usersNeverSeeEachOther() {
        pushOk(phone, List.of(change("progress", "el-private", T1, Map.of("positionSec", 10))));
        var stranger = api.register("Ajeno");
        assertThat(pull(stranger, 0).get("changes")).isEmpty();
        assertThat(api.get("/progress/el-private", stranger.access()).status()).isEqualTo(404);
        // The same ids are independent per user.
        pushOk(stranger, List.of(change("progress", "el-private", T2, Map.of("positionSec", 99))));
        assertThat(api.get("/progress/el-private", phone.access()).body().get("data").get("positionSec").asLong()).isEqualTo(10);
    }

    @Test
    void pullIsPaged() {
        var changes = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5; i++) changes.add(change("element", "e-" + i, T1, Map.of("itemId", "aws", "title", "Lesson " + i)));
        pushOk(phone, changes);
        long cursor = 0;
        var seen = new ArrayList<String>();
        for (int round = 0; round < 10; round++) {
            var page = api.get("/sync/pull?since=" + cursor + "&limit=2", laptop.access()).body();
            page.get("changes").forEach(c -> seen.add(c.get("id").asString()));
            cursor = page.get("cursor").asLong();
            if (!page.get("hasMore").asBoolean()) break;
        }
        assertThat(seen).containsExactly("e-0", "e-1", "e-2", "e-3", "e-4");
    }

    @Test
    void restViewsFollowTheSameRules() {
        assertThat(api.call("PUT", "/progress/el-r", Map.of("updatedAt", T2, "data", Map.of("positionSec", 50)), phone.access()).status()).isEqualTo(200);
        var conflict = api.call("PUT", "/progress/el-r", Map.of("updatedAt", T1, "data", Map.of("positionSec", 10)), laptop.access());
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("server").get("data").get("positionSec").asLong()).isEqualTo(50);
        var batch = api.post("/library-items", List.of(Map.of("id", "aws", "updatedAt", T1, "data", Map.of("categoryId", "courses", "title", "AWS"))), phone.access());
        assertThat(batch.body().get("applied").asInt()).isEqualTo(1);
        assertThat(api.get("/library-items", laptop.access()).body().get("changes").get(0).get("data").get("title").asString()).isEqualTo("AWS");
        // §7.3 lists GET only for /history.
        assertThat(api.post("/history", List.of(), phone.access()).status()).isEqualTo(405);
    }

    private static List<String> ids(JsonNode records) {
        var ids = new ArrayList<String>();
        records.forEach(r -> ids.add(r.get("id").asString()));
        return ids;
    }
}
