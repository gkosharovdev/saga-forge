package io.github.gkosharovdev.sagaforge.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.gkosharovdev.sagaforge.spring.events.ApplicationEventsMode;

/**
 * Saga Forge's Spring Boot properties, {@code saga-forge.*} ({@code specs/008-spring-boot-starter} data-model.md).
 * The in-memory transport's tuning lives under {@code saga-forge.transport.*} (007's {@code TransportProperties}).
 */
@ConfigurationProperties("saga-forge")
public class SagaForgeProperties {

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    /** Whether Saga Forge's auto-configuration is active. */
    private boolean enabled = true;

    /** Packages scanned for saga model classes in addition to the application's auto-configuration packages. */
    private List<String> scanPackages = new ArrayList<>();

    private final Storage storage = new Storage();

    private final Runtime runtime = new Runtime();

    private final ApplicationEvents applicationEvents = new ApplicationEvents();

    private final Telemetry telemetry = new Telemetry();

    /**
     * Checks values the binder cannot check itself.
     *
     * @throws SagaForgeConfigurationException naming the property
     */
    public void validate() {
        for (String name : scanPackages) {
            if (name == null || !PACKAGE.matcher(name).matches()) {
                throw new SagaForgeConfigurationException(
                        "saga-forge.scan-packages contains '" + name + "', which is not a Java package name.",
                        "List package names such as com.example.orders.");
            }
        }
        Duration backoff = runtime.getMaxTotalRetryBackoff();
        if (backoff == null || backoff.isNegative() || backoff.isZero()) {
            throw new SagaForgeConfigurationException(
                    "saga-forge.runtime.max-total-retry-backoff must be a positive duration, but is " + backoff + ".",
                    "Set it to a positive duration such as 30s, or remove it to use the default.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    public Storage getStorage() {
        return storage;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public ApplicationEvents getApplicationEvents() {
        return applicationEvents;
    }

    public Telemetry getTelemetry() {
        return telemetry;
    }

    /** {@code saga-forge.storage.*}. */
    public static class Storage {

        /** Which storage module to use when several are on the class path; detected when only one is. */
        private StorageType type;

        private final Postgres postgres = new Postgres();

        public StorageType getType() {
            return type;
        }

        public void setType(StorageType type) {
            this.type = type;
        }

        public Postgres getPostgres() {
            return postgres;
        }
    }

    /** {@code saga-forge.storage.postgres.*}. */
    public static class Postgres {

        /** Whether the starter creates Saga Forge's tables from schema.sql when they do not exist yet. */
        private SchemaInitialization initializeSchema = SchemaInitialization.NEVER;

        public SchemaInitialization getInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(SchemaInitialization initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    /** {@code saga-forge.runtime.*}. */
    public static class Runtime {

        /** Longest total wait a step's retry policy may add up to; longer policies fail startup. */
        private Duration maxTotalRetryBackoff = Duration.ofSeconds(30);

        public Duration getMaxTotalRetryBackoff() {
            return maxTotalRetryBackoff;
        }

        public void setMaxTotalRetryBackoff(Duration maxTotalRetryBackoff) {
            this.maxTotalRetryBackoff = maxTotalRetryBackoff;
        }
    }

    /** {@code saga-forge.application-events.*}. */
    public static class ApplicationEvents {

        /** How domain events recorded in saga units of work reach Spring application-event listeners. */
        private ApplicationEventsMode mode = ApplicationEventsMode.IN_TRANSACTION;

        public ApplicationEventsMode getMode() {
            return mode;
        }

        public void setMode(ApplicationEventsMode mode) {
            this.mode = mode;
        }
    }

    /** Storage modules the starter can select. */
    public enum StorageType {
        /** {@code saga-forge-storage-postgres}. */
        POSTGRES
    }

    /** Schema initialization policies. */
    public enum SchemaInitialization {
        /** Never create tables; missing tables fail startup. The default, for Flyway or Liquibase users. */
        NEVER,
        /** Create Saga Forge's tables from schema.sql when they do not exist yet; never alters existing tables. */
        ALWAYS
    }

    /** OpenTelemetry instrumentation of the runtime and the transport ({@code specs/010-otel-observability}). */
    public static class Telemetry {

        /** Whether the application's OpenTelemetry instance is handed to the runtime and the transport. */
        private boolean enabled = true;

        /** Record saga event and command contents ({@code toString}, at most 1,000 characters) on spans. */
        private boolean recordContents;

        /** Record association values (an order id, say) on spans. */
        private boolean recordAssociationValues;

        /** Record failure messages and exception events on spans; the failure's type is always recorded. */
        private boolean recordFailureMessages;

        private final Signal tracing = new Signal();

        private final Signal metrics = new Signal();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRecordContents() {
            return recordContents;
        }

        public void setRecordContents(boolean recordContents) {
            this.recordContents = recordContents;
        }

        public boolean isRecordAssociationValues() {
            return recordAssociationValues;
        }

        public void setRecordAssociationValues(boolean recordAssociationValues) {
            this.recordAssociationValues = recordAssociationValues;
        }

        public boolean isRecordFailureMessages() {
            return recordFailureMessages;
        }

        public void setRecordFailureMessages(boolean recordFailureMessages) {
            this.recordFailureMessages = recordFailureMessages;
        }

        public Signal getTracing() {
            return tracing;
        }

        public Signal getMetrics() {
            return metrics;
        }
    }

    /** One telemetry signal that can be switched off. */
    public static class Signal {

        /** Whether this signal is recorded. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
