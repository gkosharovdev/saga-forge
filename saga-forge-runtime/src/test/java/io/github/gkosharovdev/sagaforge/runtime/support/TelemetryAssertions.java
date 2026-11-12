package io.github.gkosharovdev.sagaforge.runtime.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * An OpenTelemetry SDK over in-memory exporters, with helpers to read what Saga Forge recorded
 * ({@code specs/010-otel-observability} tasks.md T008). Shared through the runtime's test-jar by the runtime,
 * transport, storage, fixture and starter tests.
 */
public final class TelemetryAssertions {

    private final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
    private final InMemoryMetricReader metricReader = InMemoryMetricReader.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    private List<MetricData> collected;

    /** The SDK to hand to {@code openTelemetry(...)}. */
    public OpenTelemetrySdk openTelemetry() {
        return sdk;
    }

    /** Every finished span, in end order. */
    public List<SpanData> spans() {
        return spanExporter.getFinishedSpanItems();
    }

    /** The finished spans named {@code name}, in end order. */
    public List<SpanData> named(String name) {
        return spans().stream().filter(span -> span.getName().equals(name)).toList();
    }

    /** The single finished span named {@code name}. */
    public SpanData only(String name) {
        List<SpanData> matching = named(name);
        if (matching.size() != 1) {
            throw new AssertionError("expected one span " + name + " but found " + matching.size() + ": " + names());
        }
        return matching.get(0);
    }

    /** The finished parent of {@code span}, if it was exported. */
    public Optional<SpanData> parentOf(SpanData span) {
        return spans().stream().filter(candidate -> candidate.getSpanId().equals(span.getParentSpanId()))
                .findFirst();
    }

    /** The finished children of {@code parent} named {@code name}, in end order. */
    public List<SpanData> childrenOf(SpanData parent, String name) {
        return spans().stream().filter(span -> span.getParentSpanId().equals(parent.getSpanId()))
                .filter(span -> span.getName().equals(name)).toList();
    }

    /** The names of every finished span, in end order. */
    public List<String> names() {
        return spans().stream().map(SpanData::getName).toList();
    }

    /** Clears the exported spans and the metric snapshot. */
    public void reset() {
        spanExporter.reset();
        collected = null;
    }

    /** Collects the metrics now; later reads use this snapshot until the next call. */
    public List<MetricData> collectMetrics() {
        collected = new ArrayList<>(metricReader.collectAllMetrics());
        return collected;
    }

    private List<MetricData> metrics() {
        return collected != null ? collected : collectMetrics();
    }

    /** The metric named {@code name}, if any point was recorded. */
    public Optional<MetricData> metric(String name) {
        return metrics().stream().filter(metric -> metric.getName().equals(name)).findFirst();
    }

    /** The sum of counter {@code name} over the points whose attributes contain every entry of {@code match}. */
    public long counter(String name, Attributes match) {
        return metric(name).map(metric -> metric.getLongSumData().getPoints().stream()
                        .filter(point -> contains(point.getAttributes(), match))
                        .mapToLong(LongPointData::getValue).sum())
                .orElse(0L);
    }

    /** The number of recordings of histogram {@code name} whose attributes contain every entry of {@code match}. */
    public long histogramCount(String name, Attributes match) {
        return metric(name).map(metric -> metric.getHistogramData().getPoints().stream()
                        .filter(point -> contains(point.getAttributes(), match))
                        .mapToLong(HistogramPointData::getCount).sum())
                .orElse(0L);
    }

    /** Every metric series as {@code name{key=value,...}}, sorted. */
    public Set<String> series() {
        Set<String> series = new TreeSet<>();
        for (MetricData metric : metrics()) {
            Collection<? extends PointData> points = metric.getData().getPoints();
            for (PointData point : points) {
                Map<String, String> attributes = new TreeMap<>();
                point.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), String.valueOf(value)));
                series.add(metric.getName() + attributes);
            }
        }
        return series;
    }

    /** Every attribute key used on any metric point. */
    public Set<String> metricAttributeKeys() {
        Set<String> keys = new TreeSet<>();
        metrics().forEach(metric -> metric.getData().getPoints()
                .forEach(point -> point.getAttributes().forEach((key, value) -> keys.add(key.getKey()))));
        return keys;
    }

    /** The value of {@code key} on {@code span}. */
    public static <T> T attribute(SpanData span, AttributeKey<T> key) {
        return span.getAttributes().get(key);
    }

    private static boolean contains(Attributes attributes, Attributes match) {
        boolean[] all = {true};
        match.forEach((key, value) -> {
            if (!value.equals(attributes.get(key))) {
                all[0] = false;
            }
        });
        return all[0];
    }
}
