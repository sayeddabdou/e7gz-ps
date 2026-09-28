package ps.e7gz.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shared OpenAPI conventions, without Spring (E7-11). */
class OpenApiConfigTest {

    @Test
    void aDeclaredRequiredIdempotencyKeyStaysRequiredAndIsNotDuplicated() {
        Operation operation = new Operation().addParametersItem(new HeaderParameter()
                .name("idempotency-key").required(true).schema(new StringSchema().format("uuid")));

        OpenApiConfig.addHeaderUnlessDeclared(operation, new HeaderParameter()
                .name(OpenApiConfig.IDEMPOTENCY_KEY).required(false).schema(new StringSchema()));

        List<Parameter> headers = operation.getParameters();
        assertThat(headers).hasSize(1);
        assertThat(headers.get(0).getRequired()).isTrue();
    }

    @Test
    void anUndeclaredIdempotencyKeyIsAddedAsOptional() {
        Operation operation = new Operation();

        OpenApiConfig.addHeaderUnlessDeclared(operation, new HeaderParameter()
                .name(OpenApiConfig.IDEMPOTENCY_KEY).required(false).schema(new StringSchema()));

        assertThat(operation.getParameters()).singleElement()
                .satisfies(header -> assertThat(header.getRequired()).isFalse());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void declaredErrorCodesNarrowTheSharedEnvelope() {
        Schema<?> schema = OpenApiConfig.errorWithCodes(new LinkedHashSet<>(List.of("OWNER_PASSWORD_REQUIRED",
                "OWNER_PASSWORD_INVALID")));

        assertThat(schema.getAllOf().get(0).get$ref()).isEqualTo(OpenApiConfig.ERROR_RESPONSE_REF);
        Schema error = (Schema) schema.getAllOf().get(1).getProperties().get("error");
        Schema code = (Schema) error.getProperties().get("code");
        assertThat(List.copyOf(code.getEnum())).containsExactly("OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aNullableReferenceBecomesAnyOfAndKeepsItsMetadata() {
        Schema nullableRef = new JsonSchema().$ref("#/components/schemas/Money").types(Set.of("null"))
                .description("Lowest hourly price").title("Starting price").readOnly(true).deprecated(true);
        nullableRef.setExample(Map.of("amount", "150.00", "currency", "EGP"));
        Schema plainRef = new JsonSchema().$ref("#/components/schemas/Money");
        Schema holder = new JsonSchema().types(Set.of("object"))
                .addProperty("startingPrice", nullableRef)
                .addProperty("hourlyPrice", plainRef);

        OpenApiConfig.nullableRefsAsAnyOf(holder);

        Schema<?> converted = (Schema<?>) holder.getProperties().get("startingPrice");
        assertThat(converted.get$ref()).isNull();
        assertThat(converted.getAnyOf()).extracting(Schema::get$ref).containsExactly("#/components/schemas/Money", null);
        assertThat(converted.getAnyOf().get(1).getTypes()).containsExactly("null");
        assertThat(converted.getDescription()).isEqualTo("Lowest hourly price");
        assertThat(converted.getTitle()).isEqualTo("Starting price");
        assertThat(converted.getReadOnly()).isTrue();
        assertThat(converted.getDeprecated()).isTrue();
        assertThat(converted.getExample()).isEqualTo(Map.of("amount", "150.00", "currency", "EGP"));
        assertThat(holder.getProperties().get("hourlyPrice")).isSameAs(plainRef);
    }
}
