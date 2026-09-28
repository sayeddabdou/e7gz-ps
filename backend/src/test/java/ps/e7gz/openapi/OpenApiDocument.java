package ps.e7gz.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.support.RepoFiles;

/** Reads the OpenAPI document springdoc serves and formats it the way it is committed. */
final class OpenApiDocument {

    /** The committed contract the apps, the TS client and the mock server read. */
    static final Path COMMITTED = RepoFiles.root().resolve("packages/api-client/openapi.json");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Two-space indent, LF line endings on every OS, {@code "key": value}. */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter(
            Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator("").withArrayEmptySeparator(""))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private OpenApiDocument() {
    }

    /** The document as committed: keys sorted, pretty-printed, trailing newline. */
    static String fetch(MockMvc mvc) throws Exception {
        byte[] body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        Object document = JSON.readValue(body, Object.class);
        return JSON.writer(PRETTY).writeValueAsString(document) + "\n";
    }

    static JsonNode parse(String json) throws Exception {
        return JSON.readTree(json.getBytes(StandardCharsets.UTF_8));
    }
}
