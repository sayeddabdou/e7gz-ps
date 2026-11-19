package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.identity.internal.LegalTextsProperties;

/**
 * The current Terms of Use and Privacy Policy (PCR-013 items 2-5; PO decision 16 Nov 2026, E7-124): the version label
 * a registration must accept and the two public addresses the apps link to. Public: no access token, like discovery
 * (arch 09 section 3.2), so the sign-up screen can read it. The app and the server never disagree about the current
 * version, because both take it from here.
 */
@RestController
@RequestMapping("/api/v1/customer/legal")
@Tag(name = "customer-legal")
public class CustomerLegalController {

    private final LegalTextsProperties legal;
    private final ResponseMetaFactory meta;

    public CustomerLegalController(LegalTextsProperties legal, ResponseMetaFactory meta) {
        this.legal = legal;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "customerGetLegalTexts",
            summary = "The current Terms of Use and Privacy Policy: their version and public addresses (PCR-013)",
            description = "Send version as acceptedTermsVersion when registering. No token needed.")
    public DataResponse<CustomerLegalTexts> get() {
        return meta.data(new CustomerLegalTexts(legal.termsVersion(), legal.termsUrl(), legal.privacyUrl()));
    }

    @Schema(name = "CustomerLegalTexts")
    public record CustomerLegalTexts(
            @NotNull @Schema(description = "The version label of the current pair of texts (PCR-013 item 5).",
                    example = "2026-11-15") String version,
            @NotNull @Schema(description = "Public address of the Terms of Use.",
                    example = "https://e7gz.example/terms") String termsUrl,
            @NotNull @Schema(description = "Public address of the Privacy Policy.",
                    example = "https://e7gz.example/privacy") String privacyUrl) {
    }
}
