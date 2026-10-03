package ps.e7gz.common.security.owner;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a venue command that deliberately runs without the Owner Password (FRS §2.4 "Owner Password NOT required",
 * FR-VEN-04, or a Product Owner decision recorded in the matrix). The value is the action's id in
 * {@code owner-password-actions.tsv}; the architecture test fails when it is not listed there as not required.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OwnerPasswordNotRequired {

    /** The action id in the Owner Password matrix, e.g. {@code VENUE_SETTINGS_UPDATE}. */
    String value();
}
