package ps.e7gz.common.security.owner;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a command whose venue path needs the Owner Password (FRS §2.4, ADR-011, arch 10 §3.3). The value is the
 * action's id in the Owner Password matrix the architecture test reads ({@code owner-password-actions.tsv}, which
 * mirrors FRS §2.4 and the PCRs that amend it); the test fails when an annotation and the matrix disagree.
 *
 * <p>The Owner Password gate runs only commands that carry this annotation, and audits a refused attempt under this
 * action id. The password is checked inside the command's own transaction on every run; nothing is ever unlocked.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OwnerProtected {

    /** The action id in the Owner Password matrix, e.g. {@code VENUE_PHOTO_ADD}. */
    String value();
}
