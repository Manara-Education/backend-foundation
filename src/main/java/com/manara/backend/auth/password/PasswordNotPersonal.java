package com.manara.backend.auth.password;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The new password is not the account's own address, the address's local part, or the holder's
 * name ({@link PasswordPolicy#isAboutAccount}). On the class because it needs a second field; for
 * requests implementing {@link PasswordOwner}.
 */
@Documented
@Constraint(validatedBy = PasswordNotPersonalValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordNotPersonal {

    String message() default "{validation.password.personal}";

    /** The property the refusal is reported against, so it lands on the password field. */
    String passwordField() default "password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
