package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HarnessStoresTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static SagaInstance instance(String sagaId, Map<String, String> associations, SagaStatus status) {
        return new SagaInstance(sagaId, "com.example.OrderSaga", Map.of(), associations, status, NOW, NOW,
                Map.of(), null, null);
    }

    /** Mutable aggregate POJO, as steps change aggregates in place. */
    static final class Account {
        int balance;

        Account(int balance) {
            this.balance = balance;
        }

        Account copy() {
            return new Account(balance);
        }
    }

    @Nested
    @DisplayName("InMemoryStorageProvider")
    class StorageProviderTests {

        private final InMemoryStorageProvider storage = new InMemoryStorageProvider();

        @Test
        @DisplayName("rejects null arguments with IllegalArgumentException")
        void nullRules() {
            assertThatThrownBy(() -> storage.save(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.findById(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.findByAssociation(null, "v"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.findByAssociation("p", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.restore(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("save, findById and delete round trip; save replaces by sagaId")
        void crud() {
            storage.save(instance("s1", Map.of(), SagaStatus.CREATED));
            storage.save(instance("s1", Map.of(), SagaStatus.RUNNING));

            assertThat(storage.findById("s1")).get().extracting(SagaInstance::status).isEqualTo(SagaStatus.RUNNING);
            assertThat(storage.all()).hasSize(1);

            storage.delete("s1");
            assertThat(storage.findById("s1")).isEmpty();
        }

        @Test
        @DisplayName("findByAssociation matches on property and value")
        void findByAssociation() {
            storage.save(instance("s1", Map.of("orderId", "o-1"), SagaStatus.RUNNING));
            storage.save(instance("s2", Map.of("orderId", "o-2"), SagaStatus.RUNNING));
            storage.save(instance("s3", Map.of("customerId", "o-1"), SagaStatus.RUNNING));
            storage.save(instance("s4", Map.of("orderId", "o-1"), SagaStatus.RUNNING));

            assertThat(storage.findByAssociation("orderId", "o-1"))
                    .extracting(SagaInstance::sagaId).containsExactly("s1", "s4");
            assertThat(storage.findByAssociation("orderId", "o-9")).isEmpty();
        }

        @Test
        @DisplayName("restore undoes saves and deletes made after the snapshot, and can be repeated")
        void snapshotRestore() {
            storage.save(instance("s1", Map.of(), SagaStatus.CREATED));
            Object snapshot = storage.snapshot();

            storage.save(instance("s1", Map.of(), SagaStatus.RUNNING));
            storage.save(instance("s2", Map.of(), SagaStatus.CREATED));
            storage.restore(snapshot);

            assertThat(storage.all()).extracting(SagaInstance::sagaId).containsExactly("s1");
            assertThat(storage.findById("s1")).get().extracting(SagaInstance::status).isEqualTo(SagaStatus.CREATED);

            storage.delete("s1");
            storage.restore(snapshot);
            assertThat(storage.findById("s1")).isPresent();
        }

        @Test
        @DisplayName("restore rejects a foreign snapshot")
        void foreignSnapshot() {
            Object foreign = new InMemoryAggregateStore().snapshot();
            assertThatThrownBy(() -> storage.restore(foreign)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("InMemoryAggregateStore")
    class AggregateStoreTests {

        private final InMemoryAggregateStore store = new InMemoryAggregateStore();

        @Test
        @DisplayName("rejects null arguments and wrongly typed aggregates")
        void nullRules() {
            assertThatThrownBy(() -> store.put(null, "a", new Account(1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.put(Account.class, null, new Account(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.put(Account.class, "a", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.get(Account.class, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.remove(null, "a")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.all(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.registerCopier(Account.class, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.restore(null)).isInstanceOf(IllegalArgumentException.class);

            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<Object> raw = (Class) Account.class;
            assertThatThrownBy(() -> store.put(raw, "a", "not an account"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("put, get, remove and all are keyed by (type, id)")
        void keyedByTypeAndId() {
            Account a = new Account(10);
            store.put(Account.class, "a", a);
            store.put(Account.class, "b", new Account(20));
            store.put(String.class, "a", "other type, same id");

            assertThat(store.get(Account.class, "a")).containsSame(a);
            assertThat(store.get(String.class, "a")).contains("other type, same id");
            assertThat(store.all(Account.class)).extracting(acc -> acc.balance).containsExactly(10, 20);

            assertThat(store.remove(Account.class, "a")).isTrue();
            assertThat(store.remove(Account.class, "a")).isFalse();
            assertThat(store.get(Account.class, "a")).isEmpty();
            assertThat(store.get(String.class, "a")).isPresent();
        }

        @Test
        @DisplayName("with a copier, restore undoes in-place mutations, additions and removals")
        void snapshotRestoreWithCopier() {
            store.registerCopier(Account.class, Account::copy);
            store.put(Account.class, "a", new Account(10));
            store.put(Account.class, "b", new Account(20));
            Object snapshot = store.snapshot();

            store.get(Account.class, "a").orElseThrow().balance = 999;
            store.remove(Account.class, "b");
            store.put(Account.class, "c", new Account(30));
            store.restore(snapshot);

            assertThat(store.all(Account.class)).extracting(acc -> acc.balance).containsExactly(10, 20);

            // a restored aggregate is a fresh copy, so mutating it does not corrupt the snapshot
            store.get(Account.class, "a").orElseThrow().balance = 1;
            store.restore(snapshot);
            assertThat(store.get(Account.class, "a")).get().extracting(acc -> acc.balance).isEqualTo(10);
        }

        @Test
        @DisplayName("without a copier, restore keeps references: entries roll back, in-place mutations do not")
        void snapshotRestoreWithoutCopier() {
            Account a = new Account(10);
            store.put(Account.class, "a", a);
            Object snapshot = store.snapshot();

            a.balance = 999;
            store.put(Account.class, "b", new Account(20));
            store.restore(snapshot);

            assertThat(store.all(Account.class)).hasSize(1);
            assertThat(store.get(Account.class, "a")).containsSame(a);
            assertThat(a.balance).isEqualTo(999);
        }
    }

    @Nested
    @DisplayName("InMemorySagaContextStore")
    class ContextStoreTests {

        @Test
        @DisplayName("rejects null arguments with IllegalArgumentException")
        void nullRules() {
            var contexts = new InMemorySagaContextStore();
            assertThatThrownBy(() -> contexts.find(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> contexts.save(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> contexts.delete(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("save replaces by sagaId; delete removes")
        void crud() {
            var contexts = new InMemorySagaContextStore();
            contexts.save(new SagaContextRecord("s1", Map.of("k", "v1"), NOW));
            contexts.save(new SagaContextRecord("s1", Map.of("k", "v2"), NOW));

            assertThat(contexts.find("s1")).get().extracting(r -> r.values().get("k")).isEqualTo("v2");
            assertThat(contexts.all()).hasSize(1);

            contexts.delete("s1");
            assertThat(contexts.find("s1")).isEmpty();
        }

        @Test
        @DisplayName("context written during a unit of work survives a storage and aggregate rollback")
        void survivesRollback() {
            var storage = new InMemoryStorageProvider();
            var aggregates = new InMemoryAggregateStore();
            var contexts = new InMemorySagaContextStore();
            Object storageSnapshot = storage.snapshot();
            Object aggregateSnapshot = aggregates.snapshot();

            storage.save(instance("s1", Map.of(), SagaStatus.RUNNING));
            aggregates.put(Account.class, "a", new Account(10));
            contexts.save(new SagaContextRecord("s1", Map.of("paymentId", "p-1"), NOW));

            storage.restore(storageSnapshot);
            aggregates.restore(aggregateSnapshot);

            assertThat(storage.findById("s1")).isEmpty();
            assertThat(aggregates.get(Account.class, "a")).isEmpty();
            assertThat(contexts.find("s1")).get().extracting(r -> r.values().get("paymentId")).isEqualTo("p-1");
        }
    }

    @Nested
    @DisplayName("RecordingEventDispatcher")
    class DispatcherTests {

        private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();

        @Test
        @DisplayName("rejects null arguments with IllegalArgumentException")
        void nullRules() {
            assertThatThrownBy(() -> dispatcher.dispatch(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> dispatcher.registerSagaType(null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(dispatcher.dispatched()).isEmpty();
        }

        @Test
        @DisplayName("records events in dispatch order, including ones a listener dispatches")
        void ordering() {
            List<Object> seen = new ArrayList<>();
            dispatcher.onDispatch(event -> {
                seen.add(event);
                if (event.equals("first")) {
                    dispatcher.dispatch("reentrant");
                }
            });

            dispatcher.dispatch("first");
            dispatcher.dispatch("second");

            assertThat(dispatcher.dispatched()).containsExactly("first", "reentrant", "second");
            assertThat(seen).containsExactly("first", "reentrant", "second");

            dispatcher.clearDispatched();
            assertThat(dispatcher.dispatched()).isEmpty();
        }

        @Test
        @DisplayName("records registered saga types once, in registration order")
        void registeredTypes() {
            dispatcher.registerSagaType(String.class);
            dispatcher.registerSagaType(Integer.class);
            dispatcher.registerSagaType(String.class);

            assertThat(dispatcher.registeredSagaTypes()).containsExactly(String.class, Integer.class);
        }
    }

    @Nested
    @DisplayName("MapHandlerInstanceProvider")
    class ProviderTests {

        static final class PaymentService {
        }

        @Test
        @DisplayName("returns the instance registered under its class")
        void returnsRegistered() {
            var service = new PaymentService();
            var provider = new MapHandlerInstanceProvider().register(service);

            assertThat(provider.instanceOf(PaymentService.class)).isSameAs(service);
        }

        @Test
        @DisplayName("rejects null with IllegalArgumentException and a missing instance with IllegalStateException")
        void nullAndMissing() {
            var provider = new MapHandlerInstanceProvider();

            assertThatThrownBy(() -> provider.register(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.instanceOf(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.instanceOf(PaymentService.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PaymentService.class.getName());
        }
    }
}
