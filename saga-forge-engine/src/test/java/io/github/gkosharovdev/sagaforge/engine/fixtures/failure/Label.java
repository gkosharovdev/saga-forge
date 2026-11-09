package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import java.util.Optional;

/**
 * Aggregate with an instance-method handler, loaded and saved through {@link LabelResolver}. Prints itself, fails if
 * {@link FaultPoint#LABEL_HANDLER} is armed, then records {@link LabelPrinted}.
 */
@Aggregate
public final class Label {

    @AggregateIdentifier
    private final String labelId;
    private final FaultPlan plan;
    private String printedFor;

    public Label(String labelId, FaultPlan plan) {
        this.labelId = labelId;
        this.plan = plan;
    }

    /** The label id the saga prints for a parcel. */
    public static String labelIdFor(String parcelId) {
        return "label-" + parcelId;
    }

    @CommandHandler
    public void handle(PrintLabel command) {
        printedFor = command.parcelId();
        plan.throwIfArmed(FaultPoint.LABEL_HANDLER);
        DomainEvents.record(new LabelPrinted(labelId, command.parcelId()));
    }

    public String labelId() {
        return labelId;
    }

    /** The parcel this label was printed for, if any. */
    public Optional<String> printedFor() {
        return Optional.ofNullable(printedFor);
    }

    Label copy() {
        Label copy = new Label(labelId, plan);
        copy.printedFor = printedFor;
        return copy;
    }
}
