package ps.e7gz.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.support.IntegrationTest;

/**
 * One correlation ID per request across every servlet dispatch, on a real Tomcat (MockMvc performs no real async or
 * error dispatch): the response header, {@code meta.correlationId}, the ID the code sees, and the log MDC all carry the
 * client's original ID through
 * <ul>
 *   <li>an async {@code Callable} (runs on the MVC executor, then an async dispatch renders it),</li>
 *   <li>a failing {@code Callable} and a {@code DeferredResult} failed from a foreign thread (handled on the async
 *       dispatch),</li>
 *   <li>an exception thrown by a filter and a {@code sendError} (handled on the container's error dispatch),</li>
 *   <li>a 401 written by the security chain's entry point.</li>
 * </ul>
 * Tomcat and the async executor are shrunk to force thread reuse; every request must start on a clean thread, and the
 * executor's pooled thread must be clean after its task.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.threads.max=2", "server.tomcat.threads.min-spare=1",
        "spring.task.execution.pool.core-size=1", "spring.task.execution.pool.max-size=1"})
@Import({CorrelationDispatchServerTest.Probe.class, CorrelationDispatchServerTest.Setup.class})
class CorrelationDispatchServerTest extends IntegrationTest {

    static final String BASE = "/test/e7-12-server";
    static final List<String> STATE_AT_REQUEST_START = new CopyOnWriteArrayList<>();
    static final List<String> ASYNC_THREADS = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    AsyncTaskExecutor mvcAsyncExecutor;

    final HttpClient http = HttpClient.newHttpClient();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        root().addAppender(logs);
        STATE_AT_REQUEST_START.clear();
        ASYNC_THREADS.clear();
    }

    @AfterEach
    void releaseLogs() {
        root().detachAppender(logs);
    }

    @Test
    void anAsyncCallableKeepsTheOriginalIdOnItsThreadInTheBodyHeaderAndLogs() throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = get("/callable", id);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header()).isEqualTo(id.toString());
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(id.toString());
        assertThat(r.body().path("data").path("seenOnTheAsyncThread").asText()).isEqualTo(id.toString());
        assertThat(r.body().path("data").path("mdcOnTheAsyncThread").asText()).isEqualTo(id.toString());
        assertThat(r.body().path("data").path("asyncThread").asText()).isNotEqualTo(r.body().path("data").path("requestThread").asText());
        assertThat(logged("callable ran")).allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("correlationId", id.toString()));
        assertThat(logged("callable ran")).isNotEmpty();
        assertThat(logged("controller accepted")).singleElement()
                .satisfies(e -> assertThat(e.getMDCPropertyMap()).containsEntry("correlationId", id.toString()));
    }

    @Test
    void aFailingCallableIsHandledOnTheAsyncDispatchUnderTheSameId() throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = get("/callable-fails", id);

        assertThat(r.status()).isEqualTo(500);
        assertMetaOnly(r, id);
        assertThat(logged("Unexpected failure")).singleElement()
                .satisfies(e -> assertThat(e.getMDCPropertyMap()).containsEntry("correlationId", id.toString()));
    }

    @Test
    void aDeferredResultFailedFromAForeignThreadIsRenderedUnderTheSameId() throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = get("/deferred-fails", id);

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.header()).isEqualTo(id.toString());
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(id.toString());
        assertThat(fieldNames(r.body().path("error"))).containsExactly("code");
    }

    @Test
    void anExceptionFromAFilterIsRenderedOnTheErrorDispatchUnderTheSameId() throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = get("/filter-boom", id);

        assertThat(r.status()).isEqualTo(500);
        assertMetaOnly(r, id);
        assertThat(r.raw()).doesNotContain("timestamp").doesNotContain("path").doesNotContain("secret");
        assertThat(logged("Unexpected failure outside Spring MVC")).singleElement()
                .satisfies(e -> assertThat(e.getMDCPropertyMap()).containsEntry("correlationId", id.toString()));
    }

    @Test
    void aSendErrorIsRenderedAsItsGeneralCodeUnderTheSameId() throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = get("/send-error", id);

        assertThat(r.status()).isEqualTo(429);
        assertThat(r.header()).isEqualTo(id.toString());
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(id.toString());
        assertThat(fieldNames(r.body().path("error"))).containsExactly("code");
    }

    @Test
    void theSecurityEntryPointAnswersWithTheEnvelopeUnderTheSameId() throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = get("/secure", id);

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header()).isEqualTo(id.toString());
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(id.toString());
        // Optional properties are omitted, never null (OpenAPI: field?: string, details?: object).
        assertThat(fieldNames(r.body().path("error"))).containsExactly("code");
        assertThat(fieldNames(r.body())).containsExactlyInAnyOrder("error", "meta");
    }

    @Test
    void pooledThreadsAreCleanBeforeEveryRequestAndAfterEveryAsyncTask() throws Exception {
        for (int i = 0; i < 6; i++) {
            get("/callable", UUID.randomUUID());
            get("/callable-fails", UUID.randomUUID());
            get("/filter-boom", UUID.randomUUID());
        }
        // Two Tomcat threads served 18 requests (plus their async and error dispatches): each started clean.
        assertThat(STATE_AT_REQUEST_START).isNotEmpty().allSatisfy(state -> assertThat(state).isEqualTo("clean"));
        // The single MVC executor thread ran every Callable; after them it holds nothing.
        assertThat(ASYNC_THREADS).isNotEmpty().containsOnly(ASYNC_THREADS.get(0));
        Future<String> after = mvcAsyncExecutor.submit((Callable<String>) () ->
                Thread.currentThread().getName() + "|" + CorrelationId.current().isPresent() + "|" + MDC.get(CorrelationId.MDC_KEY));
        assertThat(after.get()).isEqualTo(ASYNC_THREADS.get(0) + "|false|null");
    }

    // ================================================================================================ helpers

    record Reply(int status, String header, String raw, JsonNode body) {
    }

    private Reply get(String path, UUID id) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE + path))
                .header(CorrelationId.HEADER, id.toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body();
        return new Reply(response.statusCode(), response.headers().firstValue(CorrelationId.HEADER).orElse(null), raw,
                raw.isEmpty() ? json.nullNode() : json.readTree(raw));
    }

    private static void assertMetaOnly(Reply r, UUID id) {
        assertThat(r.header()).isEqualTo(id.toString());
        assertThat(fieldNames(r.body())).containsExactly("meta");
        assertThat(r.body().path("meta").path("correlationId").asText()).isEqualTo(id.toString());
        assertThat(r.raw()).doesNotContain("secret").doesNotContain("Exception");
    }

    private List<ILoggingEvent> logged(String messageStart) {
        return logs.list.stream().filter(e -> e.getFormattedMessage().startsWith(messageStart)).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    // ================================================================================================ fixtures

    @TestConfiguration(proxyBeanMethods = false)
    static class Setup {

        /**
         * Stands in for E7-14's chain: one secured path, and the E7-12 envelope writers registered as E7-14 will. Scoped
         * to the probe and ordered before the production chains, whose last one denies every other path (E7-22); the
         * error dispatch still goes through that last chain, as in production.
         */
        @Bean
        @Order(0)
        SecurityFilterChain testChain(HttpSecurity http, SecurityErrorHandlers errors) throws Exception {
            return http.securityMatcher(BASE + "/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(a -> a.requestMatchers(BASE + "/secure").authenticated().anyRequest().permitAll())
                    .exceptionHandling(e -> e.authenticationEntryPoint(errors.authenticationEntryPoint())
                            .accessDeniedHandler(errors.accessDeniedHandler()))
                    .build();
        }

        /** A filter that fails outside Spring MVC: the container renders it on the error dispatch. */
        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                throw new ServletException(new IllegalStateException("secret filter failure"));
            });
            registration.addUrlPatterns(BASE + "/filter-boom");
            registration.setOrder(0);
            return registration;
        }

        /** Records, before any filter runs, whether the pooled container thread still carries an ID. */
        @Bean
        ServletRequestListener cleanThreadProbe() {
            return new ServletRequestListener() {
                @Override
                public void requestInitialized(ServletRequestEvent event) {
                    boolean clean = CorrelationId.current().isEmpty() && MDC.get(CorrelationId.MDC_KEY) == null;
                    STATE_AT_REQUEST_START.add(clean ? "clean" : "leaked " + CorrelationId.current());
                }
            };
        }
    }

    @RestController
    @RequestMapping(BASE)
    static class Probe {

        private static final org.slf4j.Logger log = LoggerFactory.getLogger("e712.probe");

        private final ResponseMetaFactory meta;

        Probe(ResponseMetaFactory meta) {
            this.meta = meta;
        }

        @GetMapping("/callable")
        Callable<DataResponse<Map<String, String>>> callable() {
            log.info("controller accepted");
            String requestThread = Thread.currentThread().getName();
            return () -> {
                log.info("callable ran");
                ASYNC_THREADS.add(Thread.currentThread().getName());
                return meta.data(Map.of("seenOnTheAsyncThread", CorrelationId.require().toString(),
                        "mdcOnTheAsyncThread", MDC.get(CorrelationId.MDC_KEY),
                        "asyncThread", Thread.currentThread().getName(), "requestThread", requestThread));
            };
        }

        @GetMapping("/callable-fails")
        Callable<String> callableFails() {
            return () -> {
                ASYNC_THREADS.add(Thread.currentThread().getName());
                throw new IllegalStateException("secret async failure");
            };
        }

        @GetMapping("/deferred-fails")
        DeferredResult<String> deferredFails() {
            DeferredResult<String> result = new DeferredResult<>();
            // A foreign thread with no correlation scope completes it; the async dispatch renders it.
            new Thread(() -> result.setErrorResult(new ApiException(ApiErrorCode.NOT_FOUND))).start();
            return result;
        }

        @GetMapping("/filter-boom")
        String filterBoom() {
            return "never reached";
        }

        @GetMapping("/send-error")
        void sendError(HttpServletResponse response) throws IOException {
            response.sendError(429);
        }

        @GetMapping("/secure")
        String secure() {
            return "never reached without a token";
        }
    }
}
