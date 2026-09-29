package ps.e7gz.common.web;

import java.util.Optional;
import java.util.UUID;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;
import ps.e7gz.common.correlation.CorrelationId;

/**
 * Carries the submitting thread's correlation ID onto a pooled executor thread and removes it when the task ends
 * (NFR-32). Spring Boot applies a {@code TaskDecorator} bean to its application task executor, which runs MVC async
 * work ({@code Callable}, {@code WebAsyncTask}) and {@code @Async} methods.
 *
 * <p>The ID is captured when the task is submitted — on the request thread, inside the request's scope — so the task
 * never depends on the request thread still running. When nothing was open at submission, the task runs without an
 * ID; either way the pooled thread is left exactly as it was.
 */
@Component
public class CorrelationTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Optional<UUID> captured = CorrelationId.current();
        if (captured.isEmpty()) {
            return task;
        }
        UUID id = captured.get();
        return () -> {
            try (CorrelationId.Scope ignored = CorrelationId.open(id)) {
                task.run();
            }
        };
    }
}
