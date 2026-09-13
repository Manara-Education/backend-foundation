package com.manara.backend.auth.password;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Uses {@link PasswordPolicy#standard()} rather than an injected bean, so it behaves identically
 * under Spring's validator and under a plain {@code Validation.buildDefaultValidatorFactory()},
 * which instantiates validators itself.
 */
public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        var violation = PasswordPolicy.standard().check(password);
        if (violation.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("{" + violation.get().messageKey() + "}")
                .addConstraintViolation();
        return false;
    }
}
