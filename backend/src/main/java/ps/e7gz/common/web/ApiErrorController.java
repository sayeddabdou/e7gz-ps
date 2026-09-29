package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * The servlet container's error page ({@code /error}), for failures that never reach {@link GlobalExceptionHandler}:
 * an exception thrown by a filter, or {@code sendError} from outside Spring MVC. Replaces Spring Boot's default error
 * body (timestamp, path, message) with the arch 09 envelope, under the request's own correlation ID — the error
 * dispatch runs through {@link CorrelationIdFilter} again and reopens the ID fixed by the first dispatch.
 *
 * <p>A status with a general code gets that code; any other 4xx is a malformed request ({@code VALIDATION_FAILED});
 * everything else is the meta-only 500. The container's message and exception are logged, never sent.
 */
@Hidden // container plumbing, not part of the API contract
@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    private final ErrorResponses responses;

    public ApiErrorController(ErrorResponses responses) {
        this.responses = responses;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    ResponseEntity<Object> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status instanceof Integer value ? value : 500;
        Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        return switch (code) {
            case 400, 405, 406, 413, 415 -> coded(ApiErrorCode.VALIDATION_FAILED);
            case 401 -> coded(ApiErrorCode.UNAUTHENTICATED);
            case 403 -> coded(ApiErrorCode.FORBIDDEN);
            case 404 -> coded(ApiErrorCode.NOT_FOUND);
            case 429 -> coded(ApiErrorCode.RATE_LIMITED);
            default -> {
                if (code >= 400 && code < 500) {
                    yield coded(ApiErrorCode.VALIDATION_FAILED);
                }
                log.error("Unexpected failure outside Spring MVC (status {}, uri {})", code,
                        request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                        failure instanceof Throwable t ? t : null);
                yield responses.unexpected();
            }
        };
    }

    private ResponseEntity<Object> coded(ApiErrorCode code) {
        return responses.coded(code, null, Map.of());
    }
}
