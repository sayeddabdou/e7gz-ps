package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.notification.internal.NotificationTemplates.Rendered;

/**
 * E7-80 (final review I2, PO decision 25 Nov 2026 R1): a code may carry a wording per {@code variant} param - a split
 * refund's Wallet and Points parts each name what came back - falling back to the code's own wording.
 */
class NotificationTemplateVariantTest {

    private static NotificationTemplates templates() {
        Properties en = new Properties();
        en.setProperty("X.push.title", "Base title");
        en.setProperty("X.push.body", "Base body");
        en.setProperty("X.WALLET.push.title", "Wallet title");
        en.setProperty("X.WALLET.push.body", "{amount} EGP to your E7GZ Wallet");
        return new NotificationTemplates(Map.of(Language.EN, en));
    }

    @Test
    void theVariantsWordingIsUsedWhenItExists() {
        Rendered rendered = templates().push("X", Language.EN, Map.of("variant", "WALLET", "amount", "25.00"));

        assertThat(rendered).isEqualTo(new Rendered("Wallet title", "25.00 EGP to your E7GZ Wallet"));
    }

    @Test
    void anUnknownVariantOrNoneFallsBackToTheCodesWording() {
        assertThat(templates().push("X", Language.EN, Map.of("variant", "POINTS")))
                .isEqualTo(new Rendered("Base title", "Base body"));
        assertThat(templates().push("X", Language.EN, Map.of())).isEqualTo(new Rendered("Base title", "Base body"));
    }

    @Test
    void eachRefundPartNamesWhatCameBack() {
        NotificationTemplates real = new NotificationTemplates();
        for (String code : new String[] {RefundNotificationRules.OPERATIONAL, RefundNotificationRules.FEE}) {
            assertThat(real.push(code, Language.EN, Map.of("variant", "WALLET", "amount", "25.00")).body())
                    .as(code).contains("25.00").contains("E7GZ Wallet").doesNotContainIgnoringCase("card");
            assertThat(real.push(code, Language.EN, Map.of("variant", "POINTS", "amount", "10000")).body())
                    .as(code).contains("10000").contains("Points").doesNotContain("Wallet")
                    .doesNotContainIgnoringCase("card");
        }
    }
}
