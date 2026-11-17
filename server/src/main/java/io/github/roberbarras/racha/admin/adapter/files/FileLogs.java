package io.github.roberbarras.racha.admin.adapter.files;

import io.github.roberbarras.racha.admin.domain.AdminPorts;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The end of the server's log file (`RACHA_LOG_FILE`, written as ECS JSON lines by Spring Boot). Reads at most the
 * last few megabytes: the screen shows recent events, not the whole history.
 */
public final class FileLogs implements AdminPorts.Logs {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Map<String, Integer> RANK = Map.of("TRACE", 0, "DEBUG", 1, "INFO", 2, "WARN", 3, "ERROR", 4);

    private final Path file;
    private final JsonMapper json;

    public FileLogs(String file, JsonMapper json) {
        this.file = file == null || file.isBlank() ? null : Path.of(file);
        this.json = json;
    }

    @Override
    public String location() {
        return file == null ? null : file.toString();
    }

    @Override
    public List<AdminPorts.LogEntry> tail(int max, String minLevel) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        int threshold = RANK.getOrDefault(minLevel, 2);
        var lines = lastLines();
        var entries = new ArrayList<AdminPorts.LogEntry>();
        for (int i = lines.size() - 1; i >= 0 && entries.size() < max; i--) {
            var entry = parse(lines.get(i));
            if (entry != null && RANK.getOrDefault(entry.level(), 2) >= threshold) entries.add(entry);
        }
        return entries;
    }

    private List<String> lastLines() {
        try (var raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - MAX_BYTES);
            var bytes = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(bytes);
            var text = new String(bytes, StandardCharsets.UTF_8);
            var lines = new ArrayList<>(List.of(text.split("\n")));
            if (start > 0 && !lines.isEmpty()) lines.removeFirst(); // probably cut in half
            return lines;
        } catch (IOException e) {
            return List.of();
        }
    }

    private AdminPorts.LogEntry parse(String line) {
        if (line.isBlank()) return null;
        if (!line.startsWith("{")) return new AdminPorts.LogEntry(null, "INFO", "", line, null);
        try {
            var node = json.readTree(line);
            var error = text(node, "error.message", "error", "message");
            var type = text(node, "error.type", "error", "type");
            return new AdminPorts.LogEntry(
                    instant(text(node, "@timestamp", null, null)),
                    upper(text(node, "log.level", "log", "level")),
                    text(node, "log.logger", "log", "logger"),
                    text(node, "message", null, null),
                    type == null && error == null ? null : (type == null ? "" : type + ": ") + (error == null ? "" : error));
        } catch (RuntimeException e) {
            return new AdminPorts.LogEntry(null, "INFO", "", line, null);
        }
    }

    /** ECS fields come flat ("log.level") or nested ({"log":{"level"}}) depending on the writer: both are read. */
    private static String text(JsonNode node, String flat, String parent, String child) {
        var value = node.get(flat);
        if ((value == null || value.isNull()) && parent != null) value = node.path(parent).get(child);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String upper(String level) {
        return level == null ? "INFO" : level.toUpperCase(java.util.Locale.ROOT);
    }

    private static Instant instant(String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
