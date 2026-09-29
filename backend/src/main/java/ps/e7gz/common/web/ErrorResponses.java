package ps.e7gz.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * Builds the two failure bodies (arch 09 §1.3–§1.4) for the exception handler and for filters that answer before a
 * controller runs (the security entry point): a coded {@link ErrorResponse}, or an {@link UnexpectedErrorResponse}.
 */
@Component
public class ErrorResponses {

    private final ResponseMetaFactory meta;
    private final ObjectMapper objectMapper;

    public ErrorResponses(ResponseMetaFactory meta, ObjectMapper objectMapper) {
        this.meta = meta;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<Object> coded(ApiErrorCode code, String field, Map<String, Object> details) {
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(body(code, field, details));
    }

    public ResponseEntity<Object> unexpected() {
        return ResponseEntity.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(new UnexpectedErrorResponse(meta.meta()));
    }

    /** Writes a coded failure straight to the servlet response (for filters). */
    public void write(HttpServletResponse response, ApiErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body(code, null, Map.of()));
    }

    private ErrorResponse body(ApiErrorCode code, String field, Map<String, Object> details) {
        return new ErrorResponse(new ErrorResponse.Error(code, field, details == null || details.isEmpty() ? null : details),
                meta.meta());
    }
}
