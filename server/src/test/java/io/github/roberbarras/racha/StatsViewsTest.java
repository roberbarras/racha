package io.github.roberbarras.racha;

import static io.github.roberbarras.racha.Api.change;
import static io.github.roberbarras.racha.Api.push;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** §12 criterion 8: what clients send (views, hours, errors, events) is stored and can be queried in the database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StatsViewsTest {
    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    private static Map<String, Object> event(String type, String at, Map<String, Object> properties) {
        return Map.of("id", UUID.randomUUID().toString(), "type", type, "occurredAt", at, "properties", properties);
    }

    private static Map<String, Object> watch(String element, String at, long seconds, boolean completed) {
        var p = new HashMap<String, Object>();
        p.put("elementId", element);
        p.put("itemId", "course-1");
        p.put("categoryId", "courses");
        p.put("startedAt", at);
        p.put("endedAt", at);
        p.put("positionSec", seconds);
        p.put("completed", completed);
        p.put("playbackSpeed", 1.5);
        p.put("subtitlesLang", "es");
        p.put("seconds", seconds);
        return event("watch", at, p);
    }

    @Test
    void clientDataIsQueryableForTheAdminPanel() {
        var api = new Api(port);
        var s = api.register("Portátil");
        var user = s.userId();
        var today = LocalDate.now(ZoneOffset.UTC);
        var yesterday = today.minusDays(1);
        api.post("/sync/push", push(List.of(
                change("setting", "user:mode.current", "2026-11-15T10:00:00Z", Map.of("value", "leisure")),
                change("category", "courses", "2026-11-15T10:00:00Z", Map.of("name", "Cursos")),
                change("libraryItem", "course-1", "2026-11-15T10:00:00Z", Map.of("categoryId", "courses", "title", "AWS")),
                change("element", "e1", "2026-11-15T10:00:00Z", Map.of("itemId", "course-1", "title", "Intro", "durationSec", 600)),
                change("element", "e2", "2026-11-15T10:00:00Z", Map.of("itemId", "course-1", "title", "IAM", "durationSec", 900)),
                change("progress", "e1", "2026-11-15T10:00:00Z", Map.of("positionSec", 600, "completed", true)),
                change("history", "h1", "2026-11-15T10:00:00Z", Map.of("elementId", "e1", "watchedAt", yesterday + "T20:00:00Z", "positionSec", 600)),
                change("history", "h2", "2026-11-15T10:00:00Z", Map.of("elementId", "e2", "watchedAt", today + "T08:00:00Z", "positionSec", 100)))), s.access());

        var batch = List.of(
                event("app_open", today + "T07:59:00Z", Map.of("platform", "linux", "mode", "leisure")),
                watch("e1", yesterday + "T20:10:00Z", 600, true),
                watch("e2", today + "T08:05:00Z", 300, false),
                event("feature", today + "T08:06:00Z", Map.of("name", "pip")),
                event("feature", today + "T08:07:00Z", Map.of("name", "ab_loop")),
                event("feature", today + "T08:08:00Z", Map.of("name", "pip")),
                event("sync_failed", today + "T08:09:00Z", Map.of("code", "unreachable")),
                event("watch", today + "T08:10:00Z", Map.of("seconds", "lots")),       // malformed: rejected
                event("watch", today + "T08:10:00Z", Map.of("seconds", 60, "startedAt", "yesterday")));
        var accepted = api.post("/analytics/events", batch, s.access());
        assertThat(accepted.body().get("accepted").asInt()).isEqualTo(7);
        assertThat(accepted.body().get("rejected").asInt()).isEqualTo(2);
        api.post("/errors", List.of(Map.of("id", UUID.randomUUID().toString(), "platform", "linux", "appVersion", "0.0.1", "message", "boom",
                "stackTrace", "java.lang.IllegalStateException: boom", "occurredAt", today + "T08:11:00Z", "context", Map.of())), s.access());

        assertThat(one("select mode from stats_users where user_id = ?", user)).isEqualTo("leisure");
        assertThat(one("select platforms from stats_users where user_id = ?", user)).isEqualTo("linux");
        assertThat(jdbc.sql("select count(*) from stats_watch where user_id = ?").param(user).query(Long.class).single()).isEqualTo(2);
        var watched = jdbc.sql("select playback_speed, subtitles_lang, completed, seconds from stats_watch where user_id = ? and element_id = 'e1'")
                .param(user).query().singleRow();
        assertThat(watched).containsEntry("subtitles_lang", "es").containsEntry("completed", true).containsEntry("seconds", 600L);
        assertThat(one("select hours::text from stats_watch_hours_by_user where user_id = ?", user)).isEqualTo("0.25");
        assertThat(one("select category_name from stats_watch_hours_by_category where user_id = ?", user)).isEqualTo("Cursos");
        assertThat(one("select title from stats_watch_hours_by_item where user_id = ?", user)).isEqualTo("AWS");
        assertThat(one("select completed_elements || '/' || elements from stats_titles where user_id = ?", user)).isEqualTo("1/2");
        assertThat(one("select completed::text from stats_completed_elements where user_id = ?", user)).isEqualTo("1");
        assertThat(one("select days::text from stats_streaks where user_id = ? order by ended desc limit 1", user)).isEqualTo("2");
        assertThat(one("select detail from stats_sync_failures where user_id = ? and kind = 'client_round'", user)).isEqualTo("unreachable");
        assertThat(jdbc.sql("select uses from stats_feature_usage where feature = 'pip'").query(Long.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("select count(*) from stats_active_users_daily where day = ?").param(today).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select errors from stats_errors_by_version where platform = 'linux' and app_version = '0.0.1'").query(Long.class).single())
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select users from stats_users_by_platform where platform = 'linux'").query(Long.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select users from stats_users_by_mode where mode = 'leisure'").query(Long.class).single()).isGreaterThanOrEqualTo(1);

        var me = api.get("/stats/me?zone=UTC", s.access()).body();
        assertThat(me.get("watchSeconds").asLong()).isEqualTo(900);
        assertThat(me.get("watchByDay")).hasSize(2);
        assertThat(me.get("watchByDay").get(1).get("day").asString()).isEqualTo(today.toString());
        assertThat(me.get("watchByDay").get(1).get("seconds").asLong()).isEqualTo(300);
        assertThat(me.get("watchByCategory").get(0).get("categoryId").asString()).isEqualTo("courses");
        assertThat(me.get("watchByCategory").get(0).get("seconds").asLong()).isEqualTo(900);
    }

    private String one(String sql, UUID user) {
        return jdbc.sql(sql).param(user).query(String.class).single();
    }
}
