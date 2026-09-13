package com.manara.backend.auth.password;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A new password that meets {@link PasswordPolicy}: at least 15 code points with an upper-case
 * English letter, a digit and a symbol among them, at most 72 UTF-8 bytes, not a short pattern
 * repeated, not on the bundled common-password list, not the service's name. The rules that need
 * the account — its address, its holder's name — are {@link PasswordNotPersonal}'s.
 *
 * <p>Each rule reports its own message; the default below only satisfies the annotation contract.
 * For new passwords only: sign-in must keep accepting passwords set under an earlier rule.
 */
@Documented
@Constraint(validatedBy = ValidPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "{validation.password.size}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
