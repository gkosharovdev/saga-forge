package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passes the runtime's diagnostics on to its default logger and keeps every logged exception with the saga id it
 * names, so a failed expectation can carry the exception that failed the saga as its cause (FR-014).
 */
final class FixtureLogger implements System.Logger {

    /** One exception the runtime logged. */
    record Logged(String sagaId, Throwable thrown) {
    }

    private static final Pattern SAGA_ID = Pattern.compile("\\bsagaId=(\\S+)");

    private final System.Logger delegate;
    private final List<Logged> logged = new ArrayList<>();

    FixtureLogger(System.Logger delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public boolean isLoggable(Level level) {
        return true;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
        if (thrown != null) {
            Matcher matcher = SAGA_ID.matcher(message == null ? "" : message);
            synchronized (this) {
                logged.add(new Logged(matcher.find() ? matcher.group(1) : null, thrown));
            }
        }
        if (delegate.isLoggable(level)) {
            delegate.log(level, bundle, message, thrown);
        }
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        if (delegate.isLoggable(level)) {
            delegate.log(level, bundle, format, params);
        }
    }

    @Override
    public void log(Level level, Supplier<String> messageSupplier) {
        if (delegate.isLoggable(level)) {
            delegate.log(level, messageSupplier);
        }
    }

    synchronized List<Logged> logged() {
        return List.copyOf(logged);
    }
}
