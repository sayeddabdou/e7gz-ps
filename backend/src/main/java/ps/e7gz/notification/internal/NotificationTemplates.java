package ps.e7gz.notification.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The push and email text of each notification code (ADR-021: "notification bodies are rendered from a template
 * catalogue keyed by code and recipient language, resolved in the notification worker"). The database and the API carry
 * codes and params only; this is the one place a sentence is built, and only for a channel outside the apps (push,
 * email). The apps render the in-app list from codes themselves.
 *
 * <p>One UTF-8 file per language, {@code notification/templates_<language>.properties}, keys
 * {@code <CODE>.push.title}, {@code <CODE>.push.body}, {@code <CODE>.email.subject}, {@code <CODE>.email.body}.
 * {@code {name}} is replaced by the notification param {@code name}. Each task that emits a PUSH or EMAIL code adds its
 * copy here, reviewed by the PO (PO decision 10 Oct 2026); {@code NotificationTemplatesTest} keeps every key a catalogue
 * code that allows the channel.
 */
@Component
class NotificationTemplates {

    /** The surfaces' languages: the customer app is English at launch (FRS section 3.1); the Dashboard EN and AR. */
    enum Language {
        EN, AR
    }

    /** A rendered push or email: title + body, or subject + body. */
    record Rendered(String title, String body) {
    }

    /** A template the code lacks, or a {@code {param}} the notification does not carry: a defect, never retried. */
    static final class MissingTemplate extends RuntimeException {
        MissingTemplate(String message) {
            super(message);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    private final Map<Language, Properties> byLanguage = new EnumMap<>(Language.class);

    NotificationTemplates() {
        for (Language language : Language.values()) {
            byLanguage.put(language, load(language));
        }
    }

    /** Tests: a catalogue of their own. */
    NotificationTemplates(Map<Language, Properties> templates) {
        for (Language language : Language.values()) {
            byLanguage.put(language, templates.getOrDefault(language, new Properties()));
        }
    }

    Rendered push(String code, Language language, Map<String, String> params) {
        return render(code, language, "push.title", "push.body", params);
    }

    Rendered email(String code, Language language, Map<String, String> params) {
        return render(code, language, "email.subject", "email.body", params);
    }

    /** Every {@code (language, key)} of the catalogue, for the consistency test. */
    Map<Language, Properties> all() {
        return Map.copyOf(byLanguage);
    }

    private Rendered render(String code, Language language, String first, String second, Map<String, String> params) {
        return new Rendered(fill(template(code, language, first), code, params),
                fill(template(code, language, second), code, params));
    }

    private String template(String code, Language language, String part) {
        return Optional.ofNullable(byLanguage.get(language).getProperty(code + "." + part))
                .or(() -> Optional.ofNullable(byLanguage.get(Language.EN).getProperty(code + "." + part)))
                .orElseThrow(() -> new MissingTemplate("No " + part + " template for " + code));
    }

    private static String fill(String template, String code, Map<String, String> params) {
        Matcher placeholder = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (placeholder.find()) {
            String value = params.get(placeholder.group(1));
            if (value == null) {
                throw new MissingTemplate("The " + code + " template needs param " + placeholder.group(1));
            }
            placeholder.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        return placeholder.appendTail(out).toString();
    }

    private static Properties load(Language language) {
        String path = "notification/templates_" + language.name().toLowerCase() + ".properties";
        Properties properties = new Properties();
        try (InputStream in = NotificationTemplates.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return properties;
    }

    /** The params as text for the placeholders; nested values are not placeholders. */
    static Map<String, String> textParams(com.fasterxml.jackson.databind.JsonNode params) {
        Map<String, String> text = new HashMap<>();
        if (params != null) {
            for (Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field : params.properties()) {
                if (field.getValue().isValueNode() && !field.getValue().isNull()) {
                    text.put(field.getKey(), field.getValue().asText());
                }
            }
        }
        return text;
    }
}
