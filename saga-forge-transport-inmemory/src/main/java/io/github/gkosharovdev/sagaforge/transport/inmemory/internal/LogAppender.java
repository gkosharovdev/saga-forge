package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.log.LogEntry;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageKind;

/** Maps outgoing messages to log entries ({@code specs/007-in-memory-transport} data-model.md, research §1). */
final class LogAppender {

    private LogAppender() {
    }

    /** One entry per event, in recording order, then one per logged command, in send order. */
    static List<LogEntry> entries(OutgoingMessages batch, Instant now) {
        List<LogEntry> entries = new ArrayList<>(batch.events().size() + batch.commands().size());
        List<Object> events = batch.events();
        for (int index = 0; index < events.size(); index++) {
            Object event = events.get(index);
            entries.add(new LogEntry(batch.unitOfWorkId() + ":" + index, MessageKind.EVENT,
                    event.getClass().getName(), event, null, OrderingKeys.ofEvent(event), batch.sagaType(),
                    batch.sagaId(), now));
        }
        for (OutgoingCommand outgoing : batch.commands()) {
            Object command = outgoing.command();
            entries.add(new LogEntry(outgoing.commandId().value(), MessageKind.COMMAND, command.getClass().getName(),
                    command, outgoing.commandId(), OrderingKeys.ofCommand(command, outgoing.commandId()),
                    batch.sagaType(), batch.sagaId(), now));
        }
        return entries;
    }

    /** The entry of an event published outside any unit of work. */
    static LogEntry published(Object event, Instant now) {
        return new LogEntry(UUID.randomUUID().toString(), MessageKind.EVENT, event.getClass().getName(), event, null,
                OrderingKeys.ofEvent(event), null, null, now);
    }
}
