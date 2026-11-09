package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import java.util.List;
import java.util.Optional;

/**
 * A storage decorator that simulates a process crash at a chosen point (specs/006-saga-compensation tasks.md T053):
 * from the {@code n}-th {@code findByIdAndLock} or {@code save} call after arming, every lock and save throws
 * {@link Crash} before reaching the store, as if the process were gone: the engine's own failure write cannot land
 * either. A crash at a lock lands between two committed units of work; a crash at a save rolls the unit of work back.
 * After a crash the engine is rebuilt over the plain store ({@link UnwindFixture#restart()}).
 */
public final class CompensationFaults implements StorageProvider {

    /** Stands in for the process stopping. */
    public static final class Crash extends RuntimeException {

        public Crash(String where) {
            super("process stopped at " + where);
        }
    }

    private final StorageProvider delegate;
    private int locksUntilCrash;
    private int savesUntilCrash;
    private int locks;
    private int saves;
    private boolean crashed;

    public CompensationFaults(StorageProvider delegate) {
        this.delegate = delegate;
    }

    /** The {@code n}-th {@code findByIdAndLock} from now (1-based), and every lock or save after it, crashes. */
    public CompensationFaults crashOnLock(int n) {
        locksUntilCrash = n;
        return this;
    }

    /** The {@code n}-th {@code save} from now (1-based), and every lock or save after it, crashes. */
    public CompensationFaults crashOnSave(int n) {
        savesUntilCrash = n;
        return this;
    }

    public int locks() {
        return locks;
    }

    public int saves() {
        return saves;
    }

    @Override
    public void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
        saves++;
        if (crashed || (savesUntilCrash > 0 && --savesUntilCrash == 0)) {
            crashed = true;
            throw new Crash("save #" + saves);
        }
        delegate.save(instance, newEntries);
    }

    @Override
    public Optional<SagaInstance> findById(String sagaId) {
        return delegate.findById(sagaId);
    }

    @Override
    public Optional<SagaInstance> findByIdAndLock(String sagaId) {
        locks++;
        if (crashed || (locksUntilCrash > 0 && --locksUntilCrash == 0)) {
            crashed = true;
            throw new Crash("lock #" + locks);
        }
        return delegate.findByIdAndLock(sagaId);
    }

    @Override
    public List<SagaInstance> findByAssociation(String associationProperty, String associationValue) {
        return delegate.findByAssociation(associationProperty, associationValue);
    }

    @Override
    public void delete(String sagaId) {
        delegate.delete(sagaId);
    }
}
