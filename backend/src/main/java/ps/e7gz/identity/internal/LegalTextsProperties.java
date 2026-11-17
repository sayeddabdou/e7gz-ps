package ps.e7gz.identity.internal;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The current Terms of Use and Privacy Policy ({@code e7gz.legal.*}; PCR-013 items 2 and 5; PO decision 16 Nov 2026).
 * Deployment settings, not secrets: development and tests use the placeholder label {@code dev}; the Product Owner sets
 * the real label and the two public addresses for deployment (E7-125, E7-101).
 *
 * @param termsVersion the version label of the current pair of texts, e.g. a date such as {@code 2026-11-15}; a
 *                     registration must send exactly this one (PCR-013 item 4)
 * @param termsUrl     the public address of the Terms of Use
 * @param privacyUrl   the public address of the Privacy Policy
 */
@ConfigurationProperties("e7gz.legal")
public record LegalTextsProperties(String termsVersion, String termsUrl, String privacyUrl) {

    /** Longest label the database accepts ({@code ck_terms_acceptance_version}). */
    public static final int MAX_VERSION_LENGTH = 64;

    public LegalTextsProperties {
        termsVersion = required(termsVersion, "e7gz.legal.terms-version (E7GZ_LEGAL_TERMS_VERSION)");
        termsUrl = required(termsUrl, "e7gz.legal.terms-url (E7GZ_LEGAL_TERMS_URL)");
        privacyUrl = required(privacyUrl, "e7gz.legal.privacy-url (E7GZ_LEGAL_PRIVACY_URL)");
        if (termsVersion.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("e7gz.legal.terms-version is longer than " + MAX_VERSION_LENGTH);
        }
    }

    /** Whether a registration accepted the current texts: the exact label, nothing else (PCR-013 item 4). */
    public boolean isCurrent(String acceptedVersion) {
        return termsVersion.equals(acceptedVersion);
    }

    private static String required(String value, String name) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException(name + " must be set");
        }
        return value.strip();
    }
}
