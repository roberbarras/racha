package io.github.roberbarras.racha.admin.adapter.metrics;

import io.github.roberbarras.racha.admin.domain.AdminPorts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A handful of the runtime metrics Prometheus also gets, for the admin screen. */
public final class MicrometerMetrics implements AdminPorts.Metrics {
    private final MeterRegistry registry;

    public MicrometerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, Double> snapshot() {
        var values = new LinkedHashMap<String, Double>();
        values.put("heapUsedBytes", sum("jvm.memory.used", List.of(Tag.of("area", "heap"))));
        values.put("heapMaxBytes", sum("jvm.memory.max", List.of(Tag.of("area", "heap"))));
        values.put("processCpu", gauge("process.cpu.usage"));
        values.put("systemCpu", gauge("system.cpu.usage"));
        values.put("liveThreads", gauge("jvm.threads.live"));
        values.put("dbConnectionsActive", gauge("hikaricp.connections.active"));
        values.put("dbConnectionsMax", gauge("hikaricp.connections.max"));
        var requests = registry.find("http.server.requests").timers();
        values.put("httpRequests", (double) requests.stream().mapToLong(Timer::count).sum());
        values.put("httpServerErrors", (double) requests.stream().filter(t -> "SERVER_ERROR".equals(t.getId().getTag("outcome"))).mapToLong(Timer::count).sum());
        values.put("httpClientErrors", (double) requests.stream().filter(t -> "CLIENT_ERROR".equals(t.getId().getTag("outcome"))).mapToLong(Timer::count).sum());
        var count = requests.stream().mapToLong(Timer::count).sum();
        var total = requests.stream().mapToDouble(t -> t.totalTime(TimeUnit.SECONDS)).sum();
        values.put("httpMeanSeconds", count == 0 ? 0.0 : total / count);
        values.put("httpMaxSeconds", requests.stream().mapToDouble(t -> t.max(TimeUnit.SECONDS)).max().orElse(0.0));
        values.values().removeIf(v -> v == null || v.isNaN());
        return values;
    }

    private Double gauge(String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? null : gauge.value();
    }

    private Double sum(String name, List<Tag> tags) {
        var gauges = registry.find(name).tags(tags).gauges();
        return gauges.isEmpty() ? null : gauges.stream().mapToDouble(g -> Math.max(0, g.value())).sum();
    }
}
