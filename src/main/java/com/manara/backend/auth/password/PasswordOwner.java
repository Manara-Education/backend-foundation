package com.manara.backend.auth.password;

/**
 * A request that sets a password and also says whose account it is for, so
 * {@link PasswordNotPersonal} can compare the two.
 */
public interface PasswordOwner {

    String proposedPassword();

    String accountEmail();

    /** {@code null} when the request does not carry the holder's name. */
    String accountFullName();
}
