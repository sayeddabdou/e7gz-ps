package ps.e7gz.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ps.e7gz.common.correlation.CorrelationId;

/**
 * {@code X-Correlation-Id} at the HTTP edge (arch 09 §1.2, NFR-32): accepted when the client sends a UUID, generated
 * otherwise (the model stores it as {@code uuid}); set on the response before the request runs, so every response —
 * success, error, or a 401 from the security chain — echoes it; and available to the request as
 * {@link CorrelationId#require()} and in the log MDC.
 *
 * <p>One request keeps one ID across all its servlet dispatches. The first dispatch fixes it in the request attribute
 * {@link #ATTRIBUTE}; an async dispatch (after a {@code Callable} or {@code DeferredResult}) and an error dispatch (to
 * {@code /error}) reopen that same ID rather than reading the header again or generating a new one. Work handed to the
 * MVC async executor carries it through {@link CorrelationTaskDecorator}.
 *
 * <p>Runs first, ahead of the Spring Security filter chain (order {@code -100}). Each dispatch closes its scope in
 * {@code finally}: nothing survives into the next request served by the same thread.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Request attribute holding the request's correlation ID for every later dispatch of the same request. */
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID id = request.getAttribute(ATTRIBUTE) instanceof UUID existing ? existing
                : CorrelationId.parse(request.getHeader(CorrelationId.HEADER)).orElseGet(CorrelationId::generate);
        request.setAttribute(ATTRIBUTE, id);
        if (!response.isCommitted()) {
            response.setHeader(CorrelationId.HEADER, id.toString());
        }
        try (CorrelationId.Scope ignored = CorrelationId.open(id)) {
            chain.doFilter(request, response);
        }
    }

    /** The async dispatch resumes the same request: it must see the same ID. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** The error dispatch renders the same request's failure: it must see the same ID. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
