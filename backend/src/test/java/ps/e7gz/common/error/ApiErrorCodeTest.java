package ps.e7gz.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ps.e7gz.support.RepoFiles;

/** {@link ApiErrorCode} is exactly the enum of arch 09 §1.5: same names, same HTTP statuses. */
class ApiErrorCodeTest {

    private static final String ARCH_09 = "docs/02-architecture/09 — API Architecture & Contract Map.md";
    private static final Pattern ROW = Pattern.compile("^\\| (`[A-Z_]+`(?: · `[A-Z_]+`)*) \\| \\**([0-9]{3}(?: / [0-9]{3})?)\\** \\|");
    private static final Pattern CODE = Pattern.compile("`([A-Z_]+)`");

    @Test
    void namesAndStatusesMatchArch09() throws IOException {
        Map<String, Set<Integer>> documented = documentedCodes();

        assertThat(documented.keySet()).as("documented codes")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ApiErrorCode.values()).map(Enum::name).toList());
        for (ApiErrorCode code : ApiErrorCode.values()) {
            assertThat(documented.get(code.name())).as(code.name()).contains(code.httpStatus());
        }
    }

    @Test
    void generalCodesHaveTheApprovedStatuses() {
        assertThat(ApiErrorCode.VALIDATION_FAILED.httpStatus()).isEqualTo(400);
        assertThat(ApiErrorCode.UNAUTHENTICATED.httpStatus()).isEqualTo(401);
        assertThat(ApiErrorCode.FORBIDDEN.httpStatus()).isEqualTo(403);
        assertThat(ApiErrorCode.NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ApiErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(429);
        assertThat(Arrays.stream(ApiErrorCode.values()).map(Enum::name)).doesNotContain("INTERNAL_ERROR");
        assertThat(Arrays.stream(ApiErrorCode.values()).map(ApiErrorCode::httpStatus)).allMatch(s -> s >= 400 && s < 500);
    }

    /** Code → the statuses its §1.5 row allows ("422 / 429" allows both). */
    private static Map<String, Set<Integer>> documentedCodes() throws IOException {
        String doc = Files.readString(RepoFiles.get(ARCH_09), StandardCharsets.UTF_8);
        String section = doc.substring(doc.indexOf("## 1.5"), doc.indexOf("# 2. AUTHENTICATION"));
        Map<String, Set<Integer>> codes = new HashMap<>();
        for (String line : section.split("\\R")) {
            Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            Set<Integer> statuses = Arrays.stream(row.group(2).split(" / ")).map(Integer::valueOf).collect(Collectors.toSet());
            Matcher code = CODE.matcher(row.group(1));
            while (code.find()) {
                codes.put(code.group(1), statuses);
            }
        }
        assertThat(codes).as("rows parsed from arch 09 §1.5").hasSizeGreaterThan(50);
        return codes;
    }
}
