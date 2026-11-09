package io.github.gkosharovdev.sagaforge.engine.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import java.util.ArrayList;
import java.util.List;

/** Service-hosted handler of {@link AddNote}; records the events its {@link AddNote.Mode} names. */
public final class NoteService implements Snapshottable {

    private final List<String> notedCases = new ArrayList<>();
    private RuntimeException nextFailure;

    @CommandHandler
    public void handle(AddNote command) {
        notedCases.add(command.caseId());
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        switch (command.mode()) {
            case NOTE -> DomainEvents.record(new CaseNoted(command.caseId()));
            case CLOSE_THEN_ESCALATE -> {
                DomainEvents.record(new CaseClosed(command.caseId()));
                DomainEvents.record(new CaseEscalated(command.caseId()));
            }
        }
    }

    /** Makes the next {@code handle} call fail after noting the case. */
    public void failNext() {
        nextFailure = new IllegalStateException("NoteService failure injected");
    }

    /** The cases noted so far, in order. */
    public List<String> notedCases() {
        return List.copyOf(notedCases);
    }

    @Override
    public Object snapshot() {
        return List.copyOf(notedCases);
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof List<?> state)) {
            throw new IllegalArgumentException("Not a NoteService snapshot: " + snapshot);
        }
        notedCases.clear();
        state.forEach(caseId -> notedCases.add((String) caseId));
    }
}
