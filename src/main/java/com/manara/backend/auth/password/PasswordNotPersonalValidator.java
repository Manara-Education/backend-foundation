package com.manara.backend.auth.password;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordNotPersonalValidator implements ConstraintValidator<PasswordNotPersonal, PasswordOwner> {

    private String passwordField;

    @Override
    public void initialize(PasswordNotPersonal annotation) {
        passwordField = annotation.passwordField();
    }

    @Override
    public boolean isValid(PasswordOwner request, ConstraintValidatorContext context) {
        if (request == null || !PasswordPolicy.standard().isAboutAccount(
                request.proposedPassword(), request.accountEmail(), request.accountFullName())) {
            return true;
        }
        // Attached to the password property rather than left on the object: the validation handler
        // answers with field errors, and a class-level error would reach the client as an empty 400.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(passwordField)
                .addConstraintViolation();
        return false;
    }
}
