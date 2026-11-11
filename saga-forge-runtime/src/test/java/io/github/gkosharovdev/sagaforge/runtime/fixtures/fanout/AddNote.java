package io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by {@link AuditSaga}'s start step; handled by {@link NoteService}. */
@Command
public record AddNote(String caseId, Mode mode) {

    /** What {@link NoteService} records. */
    public enum Mode {
        /** {@link CaseNoted}: starts a {@link ReviewSaga}; {@link AuditSaga} has no step for it. */
        NOTE,
        /**
         * {@link CaseClosed}, then {@link CaseEscalated}: the first ends {@link AuditSaga}, the second is left
         * queued.
         */
        CLOSE_THEN_ESCALATE
    }
}
