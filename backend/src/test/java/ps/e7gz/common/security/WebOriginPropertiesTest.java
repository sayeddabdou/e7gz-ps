package ps.e7gz.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E7-22 - the allowed web origins are exact origins; anything looser stops the application from starting. */
class WebOriginPropertiesTest {

    @Test
    void exactOriginsAreKeptAndBlanksDropped() {
        WebOriginProperties properties = new WebOriginProperties(
                List.of(" https://venue.e7gz.example ", "", "http://localhost:5173"));

        assertThat(properties.allowedOrigins()).containsExactly("https://venue.e7gz.example", "http://localhost:5173");
        assertThat(new WebOriginProperties(null).allowedOrigins()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.e7gz.example", "https://venue.e7gz.example/", "https://venue.e7gz.example/app",
            "venue.e7gz.example", "ftp://venue.e7gz.example", "https://user@venue.e7gz.example",
            "https://venue.e7gz.example?x=1", "null"})
    void anythingButAnExactOriginIsRefused(String origin) {
        assertThatThrownBy(() -> new WebOriginProperties(List.of(origin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an exact origin");
    }

    @Test
    void aMissingOrUnlistedOriginIsNeverAllowed() {
        WebOrigins origins = new WebOrigins(new WebOriginProperties(List.of("https://venue.e7gz.example")));

        assertThat(origins.isAllowed("https://venue.e7gz.example")).isTrue();
        assertThat(origins.isAllowed(null)).isFalse();
        assertThat(origins.isAllowed("https://venue.e7gz.example.evil.example")).isFalse();
        assertThat(origins.isAllowed("HTTPS://VENUE.E7GZ.EXAMPLE")).isFalse();
    }
}
