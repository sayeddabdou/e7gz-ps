package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.support.IntegrationTest;

/**
 * ADR-021 template catalogue (PO decision 10 Oct 2026): every key names a catalogue code that allows the channel, has
 * both of its parts, and the shipped copy carries no secret. Each emitting task (E7-55, E7-80, E7-89) adds its copy and
 * this test keeps it consistent with the database catalogue.
 */
class NotificationTemplatesTest extends IntegrationTest {

    private static final Map<String, String> CHANNEL = Map.of("push", "PUSH", "email", "EMAIL");
    private static final Map<String, List<String>> PARTS = Map.of(
            "push", List.of("title", "body"), "email", List.of("subject", "body"));

    @Autowired
    DataSource appDataSource;

    @Test
    void everyTemplateIsACatalogueCodeThatAllowsItsChannelAndHasBothParts() {
        Map<String, Set<String>> allowed = new HashMap<>();
        JdbcClient.create(appDataSource).sql("SELECT code, allowed_channels FROM notification.notification_catalogue")
                .query(rs -> {
                    allowed.put(rs.getString("code"), Set.of((String[]) rs.getArray("allowed_channels").getArray()));
                });

        NotificationTemplates templates = new NotificationTemplates();
        assertThat(templates.all().get(Language.EN)).as("English copy").isNotEmpty();
        for (Map.Entry<Language, Properties> language : templates.all().entrySet()) {
            for (String key : language.getValue().stringPropertyNames()) {
                String[] parts = key.split("\\.");
                assertThat(parts).as(key).hasSize(3);
                assertThat(allowed).as(key + ": a catalogue code").containsKey(parts[0]);
                assertThat(CHANNEL).as(key + ": push or email").containsKey(parts[1]);
                assertThat(allowed.get(parts[0])).as(key + ": channel allowed").contains(CHANNEL.get(parts[1]));
                assertThat(PARTS.get(parts[1])).as(key).contains(parts[2]);
                for (String part : PARTS.get(parts[1])) {
                    assertThat(language.getValue().getProperty(parts[0] + "." + parts[1] + "." + part))
                            .as(language.getKey() + " " + parts[0] + "." + parts[1] + "." + part).isNotBlank();
                }
            }
        }
    }

    @Test
    void placeholdersAreFilledFromParamsAndAMissingParamOrTemplateIsADefect() {
        Properties en = new Properties();
        en.setProperty("POINTS_EARNED.push.title", "Points");
        en.setProperty("POINTS_EARNED.push.body", "You earned {points} points at {venue}");
        Properties ar = new Properties();
        ar.setProperty("POINTS_EARNED.push.title", "نقاط");
        NotificationTemplates templates = new NotificationTemplates(Map.of(Language.EN, en, Language.AR, ar));

        assertThat(templates.push("POINTS_EARNED", Language.EN, Map.of("points", "40", "venue", "Arena")))
                .isEqualTo(new NotificationTemplates.Rendered("Points", "You earned 40 points at Arena"));
        assertThat(templates.push("POINTS_EARNED", Language.AR, Map.of("points", "4", "venue", "A")).title())
                .isEqualTo("نقاط");                                                  // body falls back to English
        assertThatThrownBy(() -> templates.push("POINTS_EARNED", Language.EN, Map.of("points", "1")))
                .isInstanceOf(NotificationTemplates.MissingTemplate.class).hasMessageContaining("venue");
        assertThatThrownBy(() -> templates.email("POINTS_EARNED", Language.EN, Map.of()))
                .isInstanceOf(NotificationTemplates.MissingTemplate.class);
    }
}
