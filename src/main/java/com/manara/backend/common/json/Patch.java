package com.manara.backend.common.json;

/**
 * A field the payload may or may not have mentioned, and the value it gave it.
 *
 * <p>JSON has three states for an optional field and a Java bean has two. {@code {"subtitle":"x"}}
 * means set it, {@code {"subtitle":null}} means clear it, and a payload that says nothing about
 * {@code subtitle} means leave it alone — but a plain {@code String} field is {@code null} for both
 * of the last two, so a metadata-only save blanks a published course's cover. This carries the
 * missing third state in the type.
 *
 * <p><strong>Absent is Java {@code null}; present is an instance.</strong> That is the whole rule,
 * and it is what makes this work no matter how Jackson decides to build the enclosing object. A
 * field Jackson never writes keeps its {@code null} default, so it reads as absent — and
 * {@link PatchDeserializer} answers the two questions Jackson asks about missing and explicit-null
 * values so that a properties-based creator, which passes a value for every parameter whether the
 * JSON had one or not, cannot make an absent field look present.
 *
 * <p>The previous design recorded presence inside the setters, which is only correct while Jackson
 * binds through setters. Spring Boot 4 ships Jackson 3, which bound the DTO through its all-args
 * constructor instead: the setters were never called, every field looked absent, and
 * {@code subtitle} and {@code image} became silently unwritable over HTTP while every test — all
 * of which built the DTO in Java — kept passing. Presence belongs in the value, not in the
 * mechanics of how the value arrived.
 *
 * @param <T> the field's own type
 */
public final class Patch<T> {

    private final T value;

    private Patch(T value) {
        this.value = value;
    }

    /** A field the payload mentioned, carrying {@code value} — which may itself be {@code null}. */
    public static <T> Patch<T> of(T value) {
        return new Patch<>(value);
    }

    /** The value the payload gave this field. {@code null} means it asked for the field to be cleared. */
    public T value() {
        return value;
    }

    /** Whether the payload mentioned this field at all, whatever value it gave it. */
    public static boolean isPresent(Patch<?> patch) {
        return patch != null;
    }

    /** The value, or {@code null} when the payload never mentioned the field. */
    public static <T> T valueOf(Patch<T> patch) {
        return patch == null ? null : patch.value;
    }

    @Override
    public String toString() {
        return "Patch[" + value + "]";
    }
}
