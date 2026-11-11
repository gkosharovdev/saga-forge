package io.github.gkosharovdev.sagaforge.runtime.support;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link System.Logger} that records every message at every level, for asserting on engine diagnostics
 * (specs/004-saga-engine/contracts/diagnostics.md) by event name and keys rather than full text.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main} (Constitution Principle IV).</p>
 *
 * <p>Each message is parsed as a leading event name followed by {@code key=value} pairs; a value runs up to the
 * next {@code " key="} or the end of the message, so it may contain spaces (for example a {@code cause} with a
 * message). Thread-safe.</p>
 */
public final class RecordingLogger implements System.Logger {

    private static final Pattern KEY_VALUE =
            Pattern.compile("([A-Za-z][A-Za-z0-9]*)=(.*?)(?=\\s+[A-Za-z][A-Za-z0-9]*=|$)");

    private final List<Logged> records = new CopyOnWriteArrayList<>();

    /**
     * One recorded message.
     *
     * @param level   the level it was logged at
     * @param message the formatted message; never {@code null} (empty if none was given)
     * @param thrown  the attached throwable, or {@code null}
     * @param event   the leading event name, for example {@code saga.uow.committed}; empty if the message is empty
     * @param keys    the {@code key=value} pairs after the event name, in message order; unmodifiable
     */
    public record Logged(Level level, String message, Throwable thrown, String event, Map<String, String> keys) {

        public Logged {
            keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        }

        static Logged parse(Level level, String message, Throwable thrown) {
            String text = message == null ? "" : message.strip();
            int space = text.indexOf(' ');
            String event = space < 0 ? text : text.substring(0, space);
            String pairs = space < 0 ? "" : text.substring(space + 1);
            return new Logged(level, text, thrown, event, keysOf(pairs));
        }

        private static Map<String, String> keysOf(String pairs) {
            Map<String, String> keys = new LinkedHashMap<>();
            Matcher matcher = KEY_VALUE.matcher(pairs);
            while (matcher.find()) {
                keys.put(matcher.group(1), matcher.group(2).strip());
            }
            return keys;
        }
    }

    @Override
    public String getName() {
        return RecordingLogger.class.getName();
    }

    @Override
    public boolean isLoggable(Level level) {
        return true;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
        records.add(Logged.parse(level, message, thrown));
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        String message = params == null || params.length == 0 || format == null
                ? format
                : MessageFormat.format(format, params);
        records.add(Logged.parse(level, message, null));
    }

    /** @return every recorded message, in order; unmodifiable */
    public List<Logged> records() {
        return List.copyOf(records);
    }

    /**
     * @param event the event name, for example {@code saga.uow.rolledBack}; must not be {@code null}
     * @return the recorded messages with that event name, in order; unmodifiable
     * @throws IllegalArgumentException if {@code event} is {@code null}
     */
    public List<Logged> byEvent(String event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return records.stream().filter(logged -> logged.event().equals(event)).toList();
    }
}
