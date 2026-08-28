package com.manara.backend.common.exception;

/**
 * A request that is well formed and authorized but disagrees with the state the server holds.
 *
 * <p>Answered {@code 409} rather than {@code 400}, because the caller did nothing wrong: the same
 * request would have succeeded a moment earlier, and re-reading before retrying is the fix. The
 * only condition on it today is a stale aggregate save, which is exactly that shape.
 *
 * <p>Deliberately distinct from the generic {@code 409} the database-constraint handler produces.
 * That one means "something the database refused"; this one means a named business condition the
 * domain refused before any write was attempted, and it always carries an {@link ErrorCode}.
 */
public class ConflictException extends BusinessException {

    public ConflictException(ErrorCode errorCode, String messageCode, Object... args) {
        super(errorCode, messageCode, args);
    }
}
