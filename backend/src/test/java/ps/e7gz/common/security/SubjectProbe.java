package ps.e7gz.common.security;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E7-14 test fixture: one authenticated route in each route group (no production route exists yet for a signed-in
 * customer). Answers the authenticated subject. Registered only by the tests that import it, never in the contract.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SubjectProbe.Endpoints.class)
public class SubjectProbe {

    public static final String CUSTOMER = "/api/v1/customer/test-e7-14/subject";
    public static final String VENUE = "/api/v1/venue/test-e7-14/subject";
    public static final String ADMIN = "/api/v1/admin/test-e7-14/subject";

    @RestController
    static class Endpoints {

        @GetMapping({CUSTOMER, VENUE, ADMIN})
        Map<String, String> subject(@AuthenticationPrincipal AuthenticatedSubject subject) {
            return Map.of("type", subject.type().name(), "id", subject.id().toString());
        }
    }
}
