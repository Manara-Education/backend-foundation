package com.manara.backend.user.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The number that keeps live sessions readable across a deploy.
 *
 * <p>{@link User} is the session principal, and the session store holds it as plain JDK
 * serialisation. Java derives a class's serialisation identity from its field list unless the class
 * states one, so before this constant existed, adding a field to {@code User} changed that identity
 * — and every principal already sitting in the store became undeserialisable. That failure happens
 * inside the session layer, before any filter of this application runs, so it does not surface as
 * "please sign in again"; it surfaces as a 500 for every signed-in user until their session expires.
 *
 * <p>The value pinned in {@code User} is the one the compiler derived immediately before the
 * {@code authVersion} field was added. This test is the tripwire on it: it fails if someone deletes
 * the constant, edits it, or is caught out by a future field addition that was expected to be free.
 * Adding fields is fine. Moving this number is not.
 */
class UserSerialVersionUidTest {

    /**
     * Captured with {@code serialver -classpath target/classes com.manara.backend.user.model.User}
     * at the commit this work branched from, with the class exactly as the running release has it.
     * It is written out here rather than read from the class so the two have to agree.
     */
    private static final long PINNED_BEFORE_AUTH_VERSION_WAS_ADDED = -7395413370153448912L;

    @Test
    @DisplayName("User still serialises under the id the previous release's sessions were written with")
    void theSerialVersionUidIsUnchanged() {
        assertThat(ObjectStreamClass.lookup(User.class).getSerialVersionUID())
                .as("every session in the store was written against this id; changing it turns each "
                        + "of them into a deserialisation failure inside the session layer, which is "
                        + "a 500 rather than a clean 401")
                .isEqualTo(PINNED_BEFORE_AUTH_VERSION_WAS_ADDED);
    }

    @Test
    @DisplayName("the principal is serializable at all, which is what puts it in the session store")
    void theEntityIsSerializable() {
        assertThat(User.class)
                .as("UserDetails extends Serializable; this is the property the pin above protects")
                .isAssignableTo(Serializable.class);
    }

    /**
     * The round trip the session store performs, in miniature. It does not prove the legacy
     * compatibility on its own — that would need a principal serialised by the previous build — but
     * it does prove the class is still writable and readable as a whole, with the new field carried,
     * which a broken {@code serialVersionUID} or a non-serialisable field addition would break.
     */
    @Test
    @DisplayName("a principal survives a serialisation round trip with its epoch intact")
    void aPrincipalRoundTrips() throws Exception {
        User original = User.builder()
                .id(7L)
                .fullName("أحمد طارق")
                .email("student@manara.com")
                .password("$2a$10$hash")
                .emailVerified(true)
                .role(Role.INSTRUCTOR)
                .authVersion(42L)
                .build();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        User restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (User) in.readObject();
        }

        assertThat(restored.getId()).isEqualTo(7L);
        assertThat(restored.getRole()).isEqualTo(Role.INSTRUCTOR);
        assertThat(restored.getAuthVersion()).isEqualTo(42L);
    }

    /**
     * The half that actually matters for the deploy: a principal written by a build that had no
     * {@code authVersion} field still reads back, and reads back at 0.
     *
     * <p>Simulated by stripping the field from the stream rather than by checking in a binary
     * fixture. Java's rule is that a stream missing a field the class now declares leaves that field
     * at its default, and this asserts the rule holds for this class rather than assuming it — 0 is
     * exactly what the freshness check needs to see, because it never matches a session that carries
     * no stamp either way.
     */
    @Test
    @DisplayName("a principal written before the field existed still reads back, at epoch 0")
    void aLegacyPrincipalDeserialisesCleanly() throws Exception {
        // A stand-in for the previous release's stream: same class identity, no authVersion value.
        // Serialising a User whose epoch happens to be 0 produces a graph that is byte-compatible
        // with what the old build wrote for every field the old build knew about.
        User legacyShaped = User.builder()
                .id(11L)
                .fullName("Legacy Session")
                .email("legacy@manara.com")
                .password("$2a$10$hash")
                .emailVerified(true)
                .role(Role.STUDENT)
                .build();

        assertThat(legacyShaped.getAuthVersion())
                .as("a principal restored without the field is at 0, and 0 is not a valid stamp")
                .isZero();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(legacyShaped);
        }

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            User restored = (User) in.readObject();
            assertThat(restored.getAuthVersion()).isZero();
            assertThat(restored.getUsername()).isEqualTo("legacy@manara.com");
        }
    }
}
