package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The runtime's OpenTelemetry instrumentation ({@code specs/010-otel-observability} research §1 to
 * §7, contracts/instrumentation.md, contracts/telemetry-reference.md).
 *
 * <p>Spans are started with {@link #start(String)}, which makes them current until the returned
 * {@link Traced} is closed; closing ends the span and records its duration histogram {@code
 * <name>.duration} with the span's low-cardinality attributes. Saga Forge's own work here
 * (attribute building, a content's {@code toString}) is guarded: a {@link RuntimeException} is
 * logged once per kind ({@code saga.telemetry.failed}) and never reaches the unit of work (FR-012).
 *
 * <p>Thread-safe.
 */
final class SagaInstrumentation {

  /** Instrumentation scope name, shared with the in-memory transport. */
  static final String SCOPE = "io.github.gkosharovdev.sagaforge";

  static final String HANDLE = "saga_forge.handle";
  static final String ATTEMPT = "saga_forge.attempt";
  static final String STEP = "saga_forge.step";
  static final String COMMAND = "saga_forge.command";
  static final String COMPENSATION = "saga_forge.compensation";
  static final String EVENT_IGNORED = "saga_forge.event.ignored";

  /** An operator repair ({@code specs/015-saga-operations} research R13). */
  static final String OPERATION = "saga_forge.operation";

  static final AttributeKey<String> SAGA_TYPE = AttributeKey.stringKey("saga_forge.saga.type");
  static final AttributeKey<String> SAGA_ID = AttributeKey.stringKey("saga_forge.saga.id");
  static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("saga_forge.event.type");
  static final AttributeKey<String> HANDLING_OUTCOME =
      AttributeKey.stringKey("saga_forge.handling.outcome");
  static final AttributeKey<Boolean> SAGA_STARTED =
      AttributeKey.booleanKey("saga_forge.saga.started");
  static final AttributeKey<String> SAGA_OUTCOME =
      AttributeKey.stringKey("saga_forge.saga.outcome");
  static final AttributeKey<String> UNIT_OF_WORK_ID =
      AttributeKey.stringKey("saga_forge.unit_of_work.id");
  static final AttributeKey<Long> ATTEMPT_NUMBER =
      AttributeKey.longKey("saga_forge.attempt.number");
  static final AttributeKey<String> ATTEMPT_RESULT =
      AttributeKey.stringKey("saga_forge.attempt.result");
  static final AttributeKey<Long> RETRY_WAIT = AttributeKey.longKey("saga_forge.retry.wait");
  static final AttributeKey<String> FAILURE_CLASSIFICATION =
      AttributeKey.stringKey("saga_forge.failure.classification");
  static final AttributeKey<String> STEP_NAME = AttributeKey.stringKey("saga_forge.step");
  static final AttributeKey<String> COMMAND_TYPE =
      AttributeKey.stringKey("saga_forge.command.type");
  static final AttributeKey<String> COMMAND_ID = AttributeKey.stringKey("saga_forge.command.id");
  static final AttributeKey<String> COMMAND_DELIVERY =
      AttributeKey.stringKey("saga_forge.command.delivery");
  static final AttributeKey<String> COMMAND_RESULT =
      AttributeKey.stringKey("saga_forge.command.result");
  static final AttributeKey<String> COMPENSATION_STATUS =
      AttributeKey.stringKey("saga_forge.compensation.status");
  static final AttributeKey<String> COMPENSATION_TRIGGER =
      AttributeKey.stringKey("saga_forge.compensation.trigger");
  static final AttributeKey<String> ASSOCIATION_VALUE =
      AttributeKey.stringKey("saga_forge.association.value");
  static final AttributeKey<String> CONTENT = AttributeKey.stringKey("saga_forge.content");
  static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
  static final AttributeKey<String> FAILURE_MESSAGE =
      AttributeKey.stringKey("saga_forge.failure.message");
  static final AttributeKey<String> DEADLINE_NAME =
      AttributeKey.stringKey("saga_forge.deadline.name");
  static final AttributeKey<String> DEADLINE_EVENT =
      AttributeKey.stringKey("saga_forge.deadline.event");

  static final AttributeKey<String> OPERATION_ACTION =
      AttributeKey.stringKey("saga_forge.operation.action");
  static final AttributeKey<String> OPERATION_RESULT =
      AttributeKey.stringKey("saga_forge.operation.result");
  static final AttributeKey<String> OPERATOR = AttributeKey.stringKey("saga_forge.operator");
  static final AttributeKey<String> RECOVERY_KIND =
      AttributeKey.stringKey("saga_forge.recovery.kind");
  static final AttributeKey<Boolean> START_RECOVERED =
      AttributeKey.booleanKey("saga_forge.start.recovered");
  static final AttributeKey<String> SAGA_STATUS = AttributeKey.stringKey("saga_forge.saga.status");
  static final AttributeKey<Boolean> RESOLVED = AttributeKey.booleanKey("saga_forge.resolved");

  /**
   * Attributes that may appear on metrics: bounded by the model and fixed enumerations (FR-009).
   */
  static final Set<AttributeKey<?>> LOW_CARDINALITY =
      Set.of(
          SAGA_TYPE,
          EVENT_TYPE,
          HANDLING_OUTCOME,
          SAGA_STARTED,
          SAGA_OUTCOME,
          ATTEMPT_RESULT,
          FAILURE_CLASSIFICATION,
          STEP_NAME,
          COMMAND_TYPE,
          COMMAND_DELIVERY,
          COMMAND_RESULT,
          COMPENSATION_STATUS,
          COMPENSATION_TRIGGER,
          ERROR_TYPE,
          DEADLINE_NAME,
          DEADLINE_EVENT,
          OPERATION_ACTION,
          OPERATION_RESULT,
          RECOVERY_KIND,
          SAGA_STATUS,
          RESOLVED);

  static final String SAGA_STARTED_COUNTER = "saga_forge.saga.started";
  static final String SAGA_ENDED_COUNTER = "saga_forge.saga.ended";
  static final String EVENT_HANDLED_COUNTER = "saga_forge.event.handled";
  static final String RETRY_SCHEDULED_COUNTER = "saga_forge.retry.scheduled";
  static final String RETRY_EXHAUSTED_COUNTER = "saga_forge.retry.exhausted";

  /**
   * Deadlines by {@code event}: {@code started}, {@code cancelled}, {@code expired} or {@code
   * ignored} (017).
   */
  static final String DEADLINES_COUNTER = "saga_forge.deadlines";

  /** Operator repairs by {@code action} and {@code result} (015). */
  static final String OPERATIONS_COUNTER = "saga_forge.operations";

  /**
   * Recovery sweep actions by {@code kind}: {@code compensation_resumed}, {@code start_reported},
   * {@code start_failed} or {@code error} (015).
   */
  static final String RECOVERY_COUNTER = "saga_forge.recovery";

  /** Saga instances by status and resolution, refreshed at most once per interval (015). */
  static final String INSTANCES_GAUGE = "saga_forge.instances";

  private static final int CONTENT_LIMIT = 1_000;

  private final Tracer tracer;
  private final Meter meter;
  private final SagaTelemetrySettings settings;
  private final Diagnostics diagnostics;
  private final Map<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
  private final Map<String, LongCounter> counters = new ConcurrentHashMap<>();
  private final Set<String> failedKinds = ConcurrentHashMap.newKeySet();

  SagaInstrumentation(
      OpenTelemetry openTelemetry, SagaTelemetrySettings settings, Diagnostics diagnostics) {
    this.settings = settings;
    this.diagnostics = diagnostics;
    String version = versionOf(SagaInstrumentation.class);
    this.tracer =
        (settings.tracing() ? openTelemetry.getTracerProvider() : TracerProvider.noop())
            .tracerBuilder(SCOPE)
            .setInstrumentationVersion(version)
            .build();
    this.meter =
        (settings.metrics() ? openTelemetry.getMeterProvider() : MeterProvider.noop())
            .meterBuilder(SCOPE)
            .setInstrumentationVersion(version)
            .build();
  }

  /** No telemetry; for tests and defaults. */
  static SagaInstrumentation noop(Diagnostics diagnostics) {
    return new SagaInstrumentation(
        OpenTelemetry.noop(), SagaTelemetrySettings.defaults(), diagnostics);
  }

  /** Starts an {@code INTERNAL} span under the current context and makes it current. */
  Traced start(String name) {
    return start(name, SpanKind.INTERNAL, Context.current());
  }

  /** Starts a span of {@code kind} under {@code parent} and makes it current. */
  Traced start(String name, SpanKind kind, Context parent) {
    try {
      Span span = tracer.spanBuilder(name).setSpanKind(kind).setParent(parent).startSpan();
      return new Traced(name, span, span.makeCurrent(), System.nanoTime());
    } catch (RuntimeException failure) {
      failed(name, failure);
      return new Traced(name, Span.getInvalid(), Scope.noop(), System.nanoTime());
    }
  }

  /** Adds 1 to counter {@code name} with {@code attributes} (low-cardinality only). */
  void count(String name, Attributes attributes) {
    try {
      counters
          .computeIfAbsent(name, key -> meter.counterBuilder(key).build())
          .add(1, lowCardinality(attributes));
    } catch (RuntimeException failure) {
      failed(name, failure);
    }
  }

  /** Adds an event to the current span. */
  void event(String name, Attributes attributes) {
    try {
      Span.current().addEvent(name, attributes);
    } catch (RuntimeException failure) {
      failed(name, failure);
    }
  }

  Meter meter() {
    return meter;
  }

  /**
   * Registers the {@code saga_forge.instances} gauge: one series per (status, resolved), read from
   * {@code counts} at most once per {@code refresh} as measured by {@code now}; a failing read
   * keeps the last counts.
   *
   * @return closes the gauge; never throws
   */
  AutoCloseable instancesGauge(
      Supplier<InstanceCounts> counts, Duration refresh, Supplier<Instant> now) {
    CachedCounts cache = new CachedCounts(counts, refresh, now);
    try {
      ObservableLongGauge gauge =
          meter
              .gaugeBuilder(INSTANCES_GAUGE)
              .ofLongs()
              .setDescription("Saga instances by status and resolution")
              .setUnit("{instance}")
              .buildWithCallback(measurement -> cache.record(measurement, this));
      return () -> {
        try {
          gauge.close();
        } catch (RuntimeException failure) {
          failed(INSTANCES_GAUGE, failure);
        }
      };
    } catch (RuntimeException failure) {
      failed(INSTANCES_GAUGE, failure);
      return () -> {};
    }
  }

  /** The gauge's last counts and when they were read. Thread-safe. */
  private static final class CachedCounts {

    private final Supplier<InstanceCounts> source;
    private final Duration refresh;
    private final Supplier<Instant> now;
    private InstanceCounts last;
    private Instant readAt;

    CachedCounts(Supplier<InstanceCounts> source, Duration refresh, Supplier<Instant> now) {
      this.source = source;
      this.refresh = refresh;
      this.now = now;
    }

    synchronized void record(ObservableLongMeasurement measurement, SagaInstrumentation owner) {
      Instant at = now.get();
      if (last == null || !at.isBefore(readAt.plus(refresh))) {
        try {
          last = source.get();
          readAt = at;
        } catch (RuntimeException failure) {
          owner.failed(INSTANCES_GAUGE, failure);
          if (last == null) {
            return;
          }
        }
      }
      for (SagaStatus status : SagaStatus.values()) {
        long total = last.get(status);
        long unresolved =
            status == SagaStatus.FAILED
                ? last.failedUnresolved()
                : status == SagaStatus.COMPENSATION_FAILED
                    ? last.compensationFailedUnresolved()
                    : total;
        measurement.record(unresolved, Attributes.of(SAGA_STATUS, status.name(), RESOLVED, false));
        if (status == SagaStatus.FAILED || status == SagaStatus.COMPENSATION_FAILED) {
          measurement.record(
              total - unresolved, Attributes.of(SAGA_STATUS, status.name(), RESOLVED, true));
        }
      }
    }
  }

  /**
   * The event or command's {@code toString}, truncated; {@code null} unless contents are switched
   * on or it throws.
   */
  String content(Object value) {
    if (!settings.recordContents() || value == null) {
      return null;
    }
    try {
      String text = String.valueOf(value);
      return text.length() <= CONTENT_LIMIT ? text : text.substring(0, CONTENT_LIMIT);
    } catch (RuntimeException failure) {
      failed("content", failure);
      return null;
    }
  }

  /** The association value, or {@code null} unless association values are switched on. */
  String associationValue(String value) {
    return settings.recordAssociationValues() ? value : null;
  }

  private void failed(String kind, RuntimeException failure) {
    if (failedKinds.add(kind)) {
      diagnostics.telemetryFailed(kind, failure);
    }
  }

  private static Attributes lowCardinality(Attributes attributes) {
    AttributesBuilder kept = Attributes.builder();
    attributes.forEach(
        (key, value) -> {
          if (LOW_CARDINALITY.contains(key)) {
            putUnchecked(kept, key, value);
          }
        });
    return kept.build();
  }

  @SuppressWarnings("unchecked")
  private static void putUnchecked(AttributesBuilder builder, AttributeKey<?> key, Object value) {
    builder.put((AttributeKey<Object>) key, value);
  }

  private static String versionOf(Class<?> type) {
    String version =
        type.getPackage() == null ? null : type.getPackage().getImplementationVersion();
    return version == null ? "unknown" : version;
  }

  /**
   * One started span, current until {@link #close()}. Attributes set through {@link #set} go on the
   * span; the low-cardinality ones also go on the duration histogram recorded at close. Not
   * thread-safe: used by the thread that started it.
   */
  final class Traced implements AutoCloseable {

    private final String name;
    private final Span span;
    private final Scope scope;
    private final long startNanos;
    private final AttributesBuilder metricAttributes = Attributes.builder();
    private boolean closed;

    private Traced(String name, Span span, Scope scope, long startNanos) {
      this.name = name;
      this.span = span;
      this.scope = scope;
      this.startNanos = startNanos;
    }

    /** Sets an attribute; a {@code null} value is skipped. */
    <T> Traced set(AttributeKey<T> key, T value) {
      if (value == null) {
        return this;
      }
      try {
        span.setAttribute(key, value);
        if (LOW_CARDINALITY.contains(key)) {
          metricAttributes.put(key, value);
        }
      } catch (RuntimeException failure) {
        failed(name, failure);
      }
      return this;
    }

    /** Adds an event to this span. */
    Traced event(String eventName, Attributes attributes) {
      try {
        span.addEvent(eventName, attributes);
      } catch (RuntimeException failure) {
        failed(name, failure);
      }
      return this;
    }

    /**
     * Marks this span failed: status {@code ERROR}, {@code error.type}, and the message if switched
     * on.
     */
    Traced fail(Throwable failure) {
      if (failure == null) {
        return this;
      }
      try {
        set(ERROR_TYPE, failure.getClass().getName());
        span.setStatus(StatusCode.ERROR);
        if (settings.recordFailureMessages()) {
          set(FAILURE_MESSAGE, failure.getMessage());
          span.recordException(failure);
        }
      } catch (RuntimeException broken) {
        failed(name, broken);
      }
      return this;
    }

    /** Marks this span failed with a recorded failure's type (no exception at hand). */
    Traced failType(String causeType, String message) {
      try {
        set(ERROR_TYPE, causeType);
        span.setStatus(StatusCode.ERROR);
        if (settings.recordFailureMessages()) {
          set(FAILURE_MESSAGE, message);
        }
      } catch (RuntimeException broken) {
        failed(name, broken);
      }
      return this;
    }

    /** The span's context, for propagation and links. */
    Span span() {
      return span;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        scope.close();
      } catch (RuntimeException failure) {
        failed(name, failure);
      }
      try {
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        histograms
            .computeIfAbsent(
                name, key -> meter.histogramBuilder(key + ".duration").setUnit("s").build())
            .record(seconds, metricAttributes.build());
      } catch (RuntimeException failure) {
        failed(name, failure);
      }
      try {
        span.end();
      } catch (RuntimeException failure) {
        failed(name, failure);
      }
    }
  }
}
