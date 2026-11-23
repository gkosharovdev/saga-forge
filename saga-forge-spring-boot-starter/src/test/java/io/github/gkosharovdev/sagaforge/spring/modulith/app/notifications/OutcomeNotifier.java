package io.github.gkosharovdev.sagaforge.spring.modulith.app.notifications;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;

/**
 * Another module's listeners for failed sagas ({@code specs/011-saga-outcome-events} C4): a module listener and a
 * plain transactional listener, both after the commit of the unit of work that recorded the failure.
 */
@Component
public class OutcomeNotifier {

    private final List<String> moduleListener = new CopyOnWriteArrayList<>();
    private final List<String> transactionalListener = new CopyOnWriteArrayList<>();

    @ApplicationModuleListener
    public void onModule(SagaFailed event) {
        moduleListener.add(event.eventId());
    }

    @TransactionalEventListener
    public void onCommit(SagaFailed event) {
        transactionalListener.add(event.eventId());
    }

    public List<String> moduleListener() {
        return List.copyOf(moduleListener);
    }

    public List<String> transactionalListener() {
        return List.copyOf(transactionalListener);
    }
}
