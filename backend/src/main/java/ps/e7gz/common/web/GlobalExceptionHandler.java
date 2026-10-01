package ps.e7gz.common.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.error.DatabaseErrorCodes;

/**
 * One translation of every failure into the envelope of arch 09 §1.3–§1.4. The body never contains a sentence
 * (ADR-021): a coded failure carries {@code error.code}, the input at fault and machine-readable details; anything
 * unexpected is a 500 carrying only {@code meta} — its cause is logged with the correlation ID, never sent.
 *
 * <ul>
 *   <li>{@link ApiException} — the application's decision, with its code's documented status;</li>
 *   <li>a database refusal — {@link DatabaseErrorCodes} by SQLSTATE and guard/constraint identifier; an unmapped
 *       guard, an internal invariant, or {@code 40001} after {@code CommandRunner}'s retries is unexpected (arch 09:
 *       "a guard exception the application did not anticipate is a defect");</li>
 *   <li>malformed requests — {@code VALIDATION_FAILED}; unknown routes — {@code NOT_FOUND}; security —
 *       {@code UNAUTHENTICATED} / {@code FORBIDDEN}.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorResponses responses;

    public GlobalExceptionHandler(ErrorResponses responses) {
        this.responses = responses;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> apiException(ApiException e) {
        return responses.coded(e.code(), e.field(), e.details());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> database(DataAccessException e) {
        Optional<ApiErrorCode> code = DatabaseErrorCodes.translate(e);
        if (code.isPresent()) {
            log.debug("Database refusal mapped to {}", code.get());
            return responses.coded(code.get(), null, Map.of());
        }
        String sqlState = DatabaseErrorCodes.serverError(e).map(m -> m.getSQLState()).orElse("n/a");
        String constraint = DatabaseErrorCodes.serverError(e).map(m -> m.getConstraint()).orElse(null);
        if ("40001".equals(sqlState)) {
            log.error("Serialization failure (40001) after CommandRunner exhausted its retries", e);
        } else {
            // Sev-2 signal (arch 09 §1.5): an unanticipated guard is a defect in the Java decision or the mapping.
            log.error("Unmapped database error sqlState={} constraint={}", sqlState, constraint, e);
        }
        return responses.unexpected();
    }

    /** Bean validation of a request body or of a {@code @ModelAttribute}. */
    @ExceptionHandler(BindException.class)
    ResponseEntity<Object> invalidBody(BindException e) {
        List<String> fields = e.getFieldErrors().stream().map(FieldError::getField).distinct().sorted().toList();
        return validationFailed(fields);
    }

    /**
     * Built-in MVC method validation. Invalid <b>input</b> ({@code @PathVariable}, {@code @RequestParam}, headers) is the
     * client's 400; an invalid <b>return value</b> is the server's own defect: the meta-only 500 (arch 09 §1.4).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Object> methodValidation(HandlerMethodValidationException e) {
        if (e.isForReturnValue()) {
            log.error("Controller return value failed validation: {}", e.getMethod(), e);
            return responses.unexpected();
        }
        List<String> fields = e.getParameterValidationResults().stream()
                .map(r -> r.getMethodParameter().getParameterName()).distinct().sorted().toList();
        return validationFailed(fields);
    }

    /**
     * Bean validation through {@code @Validated} (AOP). Only violations of a controller method's <b>parameters</b> are
     * request input (400); a violated return value, or a violation raised by a service the controller called, is a
     * server defect (meta-only 500).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException e) {
        boolean requestInput = !e.getConstraintViolations().isEmpty() && e.getConstraintViolations().stream()
                .allMatch(GlobalExceptionHandler::isControllerParameter);
        if (!requestInput) {
            log.error("Constraint violation outside request input", e);
            return responses.unexpected();
        }
        List<String> fields = e.getConstraintViolations().stream()
                .map(v -> leaf(v.getPropertyPath())).distinct().sorted().toList();
        return validationFailed(fields);
    }

    private static boolean isControllerParameter(ConstraintViolation<?> violation) {
        boolean parameter = false;
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.RETURN_VALUE) {
                return false;
            }
            parameter |= node.getKind() == ElementKind.PARAMETER;
        }
        Class<?> bean = violation.getRootBeanClass();
        return parameter && bean != null && (AnnotatedElementUtils.hasAnnotation(bean, Controller.class));
    }

    private static String leaf(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e) {
        return validationFailed(List.of(e.getName()));
    }

    /** A required parameter, header, path variable or cookie is absent: the field names it, as for an invalid one. */
    @ExceptionHandler(MissingRequestValueException.class)
    ResponseEntity<Object> missingValue(MissingRequestValueException e) {
        String field = switch (e) {
            case MissingServletRequestParameterException p -> p.getParameterName();
            case MissingRequestHeaderException h -> h.getHeaderName();
            case MissingPathVariableException v -> v.getVariableName();
            case MissingRequestCookieException c -> c.getCookieName();
            default -> null;
        };
        return responses.coded(ApiErrorCode.VALIDATION_FAILED, field, Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeException.class,
            HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Object> malformed(Exception e) {
        // arch 09 §1.4: 400 "malformed request" — an unreadable body, wrong media type or method is one.
        return responses.coded(ApiErrorCode.VALIDATION_FAILED, null, Map.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<Object> noRoute(Exception e) {
        return responses.coded(ApiErrorCode.NOT_FOUND, null, Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(AuthenticationException e) {
        return responses.coded(ApiErrorCode.UNAUTHENTICATED, null, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException e) {
        return responses.coded(ApiErrorCode.FORBIDDEN, null, Map.of());
    }

    /**
     * A bare status from framework code or a contract stub. 501 (an {@code Sx-P0} stub, {@code NotImplementedYet})
     * stays 501 with no body: there is no code for it and it disappears when the endpoint is implemented.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Object> status(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        return switch (status.value()) {
            case 400 -> responses.coded(ApiErrorCode.VALIDATION_FAILED, null, Map.of());
            case 401 -> responses.coded(ApiErrorCode.UNAUTHENTICATED, null, Map.of());
            case 403 -> responses.coded(ApiErrorCode.FORBIDDEN, null, Map.of());
            case 404 -> responses.coded(ApiErrorCode.NOT_FOUND, null, Map.of());
            case 429 -> responses.coded(ApiErrorCode.RATE_LIMITED, null, Map.of());
            case 501 -> ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            default -> unexpected(e);
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        log.error("Unexpected failure", e);
        return responses.unexpected();
    }

    private ResponseEntity<Object> validationFailed(List<String> fields) {
        String field = fields.size() == 1 ? fields.get(0) : null;
        Map<String, Object> details = fields.size() > 1 ? Map.of("fields", fields) : Map.of();
        return responses.coded(ApiErrorCode.VALIDATION_FAILED, field, details);
    }
}
