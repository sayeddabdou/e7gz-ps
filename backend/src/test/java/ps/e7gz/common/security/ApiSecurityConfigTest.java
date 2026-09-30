package ps.e7gz.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.support.IntegrationTest;

/** E7-13 review - API documentation and operations endpoints are closed until E7-14 decides otherwise. */
@AutoConfigureMockMvc
class ApiSecurityConfigTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml", "/swagger-ui.html",
            "/swagger-ui/index.html", "/actuator", "/actuator/health", "/actuator/health/liveness", "/actuator/info"})
    void docsAndOperationsEndpointsAreDenied(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/customer/areas", "/api/v1/customer/venues/map"})
    void publicDiscoveryIsNotBlockedBySecurity(String path) throws Exception {
        // Contract stubs (501) or missing query parameters (400): the point is that security lets them through.
        mvc.perform(get(path)).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }
}
