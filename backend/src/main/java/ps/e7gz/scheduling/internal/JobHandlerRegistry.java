package ps.e7gz.scheduling.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.scheduling.api.ScheduledJobHandler;

/**
 * Every {@link ScheduledJobHandler} of the worker, by job type. The engine knows nothing else about a type: a module
 * plugs in a timer by adding a handler bean. A type no handler claims is never polled; its jobs wait, untouched.
 */
final class JobHandlerRegistry {

    /** {@code ck_scheduled_job_type_format}. */
    private static final Pattern TYPE_FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final Map<String, ScheduledJobHandler> byType = new LinkedHashMap<>();

    JobHandlerRegistry(Collection<? extends ScheduledJobHandler> handlers) {
        for (ScheduledJobHandler handler : handlers) {
            String type = handler.type();
            if (type == null || !TYPE_FORMAT.matcher(type).matches()) {
                throw new IllegalStateException("Job type '" + type + "' does not match ck_scheduled_job_type_format");
            }
            if (byType.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Two handlers fire the job type " + type);
            }
        }
    }

    Optional<ScheduledJobHandler> handler(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    Set<String> types() {
        return byType.keySet();
    }

    List<RecurringJob> recurring() {
        return byType.values().stream()
                .filter(RecurringJob.class::isInstance).map(RecurringJob.class::cast).toList();
    }
}
