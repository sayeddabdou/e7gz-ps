package ps.e7gz.common.web;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * The OpenAPI 3.1 document generated from the controllers (ADR-012, arch 09). It is written to
 * {@code packages/api-client/openapi.json} by {@code pnpm api:gen} and is the contract the three apps and the mock
 * server use. Route groups ({@code /api/v1/customer|venue|admin/**}) come from the controllers' own paths.
 *
 * <p>Conventions every endpoint shares are added here once, not per controller (arch 09 §1.2–§1.4):
 * <ul>
 *   <li>the Bearer scheme;</li>
 *   <li>the general error responses of {@link #addSharedErrors} (400, 401, 403, 404, 429, 500);</li>
 *   <li>the optional {@code X-Correlation-Id} request header, echoed on every response;</li>
 *   <li>the {@code Idempotency-Key} header on every write — optional (CLAUDE.md §5: every write accepts it) unless the
 *       operation declares it itself, which ADR-015's scope requires with {@code @RequestHeader(IDEMPOTENCY_KEY)}; a
 *       declared header is never duplicated or relaxed;</li>
 *   <li>the error responses declared with {@link ApiError}, whose {@code error.code} is narrowed to the declared
 *       codes.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** Name of the Bearer JWT security scheme; controllers reference it with {@code @SecurityRequirement}. */
    public static final String BEARER = "bearerAuth";

    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    static final String ERROR_RESPONSE_REF = "#/components/schemas/ErrorResponse";
    static final String UNEXPECTED_ERROR_RESPONSE_REF = "#/components/schemas/UnexpectedErrorResponse";

    static {
        // Venue-local wall-clock times (opening hours) go out as ISO_LOCAL_TIME, e.g. "12:00:00" (no offset, so not the
        // JSON Schema "time" format).
        SpringDocUtils.getConfig().replaceWithSchema(LocalTime.class,
                new StringSchema().pattern("^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$").example("12:00:00"));
    }

    @Bean
    OpenAPI e7gzOpenApi() {
        return new OpenAPI()
                .info(new Info().title("E7GZ PS API").version("v1"))
                // Fixed, relative server: the committed contract must not depend on the host that generated it.
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access JWT (arch 09 §1.2). Not needed for registration, login, OTP and public "
                                + "discovery.")));
    }

    /** Error responses declared with {@link ApiError}, grouped by status. */
    @Bean
    OperationCustomizer apiErrorResponses() {
        return (operation, handlerMethod) -> {
            Map<Integer, Set<String>> byStatus = new TreeMap<>();
            for (ApiError error : handlerMethod.getMethod().getAnnotationsByType(ApiError.class)) {
                byStatus.computeIfAbsent(error.status(), status -> new LinkedHashSet<>())
                        .addAll(Arrays.stream(error.codes()).map(Enum::name).toList());
            }
            byStatus.forEach((status, codes) -> operation.getResponses().addApiResponse(String.valueOf(status),
                    new ApiResponse()
                            .description(describe(codes))
                            .content(json(errorWithCodes(codes)))));
            return operation;
        };
    }

    /** Headers and 401 shared by every operation; the error envelope schema; nullable references. */
    @Bean
    OpenApiCustomizer sharedConventions() {
        return openApi -> {
            for (Class<?> envelope : List.of(ErrorResponse.class, UnexpectedErrorResponse.class)) {
                ResolvedSchema resolved = ModelConverters.getInstance(true)
                        .resolveAsResolvedSchema(new AnnotatedType(envelope).resolveAsRef(true));
                resolved.referencedSchemas.forEach(openApi.getComponents()::addSchemas);
            }
            openApi.getComponents().getSchemas().values().forEach(OpenApiConfig::nullableRefsAsAnyOf);

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                addHeaderUnlessDeclared(operation, new HeaderParameter().name(CORRELATION_ID).required(false)
                        .description("Accepted; generated when absent; echoed on every response (arch 09 §1.2).")
                        .schema(new StringSchema()));
                if (isWrite(method)) {
                    addHeaderUnlessDeclared(operation, new HeaderParameter().name(IDEMPOTENCY_KEY).required(false)
                            .description("Accepted on every write; a replay returns the stored response (ADR-015).")
                            .schema(new StringSchema().format("uuid")));
                }
                addSharedErrors(operation);
                operation.getResponses().values().forEach(response -> response.addHeaderObject(CORRELATION_ID,
                        new Header().schema(new StringSchema())));
            }));
        };
    }

    /**
     * The general failures every operation of a kind can return (arch 09 §1.4; codes approved 29 Sep 2026, E7-12):
     * 400 {@code VALIDATION_FAILED} when it takes input, 401 {@code UNAUTHENTICATED} and 403 {@code FORBIDDEN} when it
     * is secured, 404 {@code NOT_FOUND} when it addresses a resource by path, 429 {@code RATE_LIMITED} (the general
     * per-IP limit, arch 09 §6) and 500 carrying only {@code meta}. A status the operation already declares with
     * {@link ApiError} keeps its codes and gains the general one; any other explicit response is left untouched.
     */
    static void addSharedErrors(Operation operation) {
        boolean secured = operation.getSecurity() != null && !operation.getSecurity().isEmpty();
        List<Parameter> parameters = operation.getParameters() == null ? List.of() : operation.getParameters();
        boolean takesInput = operation.getRequestBody() != null || parameters.stream()
                .anyMatch(p -> !("header".equals(p.getIn()) && CORRELATION_ID.equalsIgnoreCase(p.getName())));
        boolean addressesResource = parameters.stream().anyMatch(p -> "path".equals(p.getIn()));

        if (takesInput) {
            addSharedError(operation, 400, ApiErrorCode.VALIDATION_FAILED);
        }
        if (secured) {
            addSharedError(operation, 401, ApiErrorCode.UNAUTHENTICATED);
            addSharedError(operation, 403, ApiErrorCode.FORBIDDEN);
        }
        if (addressesResource) {
            addSharedError(operation, 404, ApiErrorCode.NOT_FOUND);
        }
        addSharedError(operation, 429, ApiErrorCode.RATE_LIMITED);
        if (operation.getResponses().get("500") == null) {
            operation.getResponses().addApiResponse("500", new ApiResponse()
                    .description("Unexpected failure: only the correlation ID, never a code or message.")
                    .content(json(new JsonSchema().$ref(UNEXPECTED_ERROR_RESPONSE_REF))));
        }
    }

    /** Adds {@code code} to the error response for {@code status}, creating it when absent (see above). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void addSharedError(Operation operation, int status, ApiErrorCode code) {
        String key = String.valueOf(status);
        ApiResponse existing = operation.getResponses().get(key);
        if (existing == null) {
            Set<String> codes = new LinkedHashSet<>(List.of(code.name()));
            operation.getResponses().addApiResponse(key, new ApiResponse()
                    .description(describe(codes)).content(json(errorWithCodes(codes))));
            return;
        }
        Schema narrowed = narrowedCodeSchema(existing);
        if (narrowed == null) {
            return; // an explicit response in another shape: never overwritten
        }
        List<Object> codes = new ArrayList<>(narrowed.getEnum());
        if (!codes.contains(code.name())) {
            codes.add(code.name());
            narrowed.setEnum(codes);
            existing.description(describe(codes.stream().map(String::valueOf).toList()));
        }
    }

    /** The {@code error.code} schema of a response built by {@link #errorWithCodes}, or null for any other shape. */
    @SuppressWarnings("rawtypes")
    private static Schema narrowedCodeSchema(ApiResponse response) {
        if (response.getContent() == null || response.getContent().get("application/json") == null) {
            return null;
        }
        Schema<?> schema = response.getContent().get("application/json").getSchema();
        if (schema == null || schema.getAllOf() == null || schema.getAllOf().size() != 2
                || !ERROR_RESPONSE_REF.equals(schema.getAllOf().get(0).get$ref())) {
            return null;
        }
        Schema error = (Schema) schema.getAllOf().get(1).getProperties().get("error");
        Schema code = error == null || error.getProperties() == null ? null : (Schema) error.getProperties().get("code");
        return code == null || code.getEnum() == null ? null : code;
    }

    private static String describe(Collection<String> codes) {
        return "error.code: " + String.join(" | ", codes);
    }

    /**
     * Adds a shared header unless the operation already declares one with that name (header names are
     * case-insensitive). A declared header keeps its own {@code required} flag.
     */
    static void addHeaderUnlessDeclared(Operation operation, Parameter header) {
        boolean declared = operation.getParameters() != null && operation.getParameters().stream().anyMatch(existing ->
                "header".equals(existing.getIn()) && header.getName().equalsIgnoreCase(existing.getName()));
        if (!declared) {
            operation.addParametersItem(header);
        }
    }

    /**
     * The shared {@link ErrorResponse} envelope with {@code error.code} narrowed to the declared codes: meta, field and
     * details stay exactly as the envelope defines them.
     */
    static Schema<?> errorWithCodes(Set<String> codes) {
        JsonSchema code = new JsonSchema();
        code.setTypes(Set.of("string"));
        code.setEnum(new ArrayList<>(codes));
        Schema<?> narrowed = new JsonSchema().types(Set.of("object"))
                .addProperty("error", new JsonSchema().types(Set.of("object"))
                        .addProperty("code", code)
                        .required(List.of("code")))
                .required(List.of("error"));
        return new JsonSchema().allOf(List.of(new JsonSchema().$ref(ERROR_RESPONSE_REF), narrowed));
    }

    /**
     * springdoc writes a nullable reference as {@code {"$ref": X, "type": "null"}}, which in OpenAPI 3.1 only matches
     * null (and the TS generator drops the null). The intended type is "X or null": {@code anyOf: [X, null]}. The
     * annotations beside the reference (description, example, title, access mode, deprecation) are kept.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void nullableRefsAsAnyOf(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        properties.replaceAll((name, property) -> {
            boolean nullable = (property.getTypes() != null && property.getTypes().contains("null"))
                    || "null".equals(property.getType());
            if (property.get$ref() == null || !nullable) {
                return property;
            }
            Schema anyOf = new JsonSchema()
                    .anyOf(List.of(new JsonSchema().$ref(property.get$ref()), new JsonSchema().types(Set.of("null"))))
                    .description(property.getDescription())
                    .title(property.getTitle())
                    .readOnly(property.getReadOnly())
                    .writeOnly(property.getWriteOnly())
                    .deprecated(property.getDeprecated());
            if (property.getExample() != null) {
                anyOf.setExample(property.getExample());
            }
            if (property.getExamples() != null) {
                anyOf.setExamples(property.getExamples());
            }
            return anyOf;
        });
    }

    private static boolean isWrite(PathItem.HttpMethod method) {
        return method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT
                || method == PathItem.HttpMethod.PATCH || method == PathItem.HttpMethod.DELETE;
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }
}
