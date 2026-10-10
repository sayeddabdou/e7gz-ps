package ps.e7gz.notification.internal.email;

import java.util.Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** The email adapter of the worker, which alone sends notifications (arch 12 section 1.3). */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(EmailProperties.class)
class EmailConfig {

    @Bean
    EmailSender emailSender(EmailProperties properties) {
        return switch (properties.provider()) {
            case LOG -> new LoggingEmailSender();
            case SMTP -> new SmtpEmailSender(mailSender(properties.smtp()), properties.from());
        };
    }

    /** Not a bean: Spring Boot's own mail auto-configuration stays off (no {@code spring.mail.host}). */
    private static JavaMailSenderImpl mailSender(EmailProperties.Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(blankToNull(smtp.username()));
        sender.setPassword(blankToNull(smtp.password()));
        sender.setDefaultEncoding("UTF-8");
        Properties mail = sender.getJavaMailProperties();
        mail.put("mail.smtp.auth", String.valueOf(blankToNull(smtp.username()) != null));
        mail.put("mail.smtp.starttls.enable", String.valueOf(smtp.startTls()));
        mail.put("mail.smtp.starttls.required", String.valueOf(smtp.startTls()));
        mail.put("mail.smtp.connectiontimeout", String.valueOf(smtp.connectTimeout().toMillis()));
        mail.put("mail.smtp.timeout", String.valueOf(smtp.readTimeout().toMillis()));
        mail.put("mail.smtp.writetimeout", String.valueOf(smtp.readTimeout().toMillis()));
        return sender;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
