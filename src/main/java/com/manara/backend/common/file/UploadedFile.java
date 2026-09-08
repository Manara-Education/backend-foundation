package com.manara.backend.common.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A file that was stored, and the account that stored it.
 *
 * <p>This is the fact the platform never had. {@code FileUploadService} generates a UUID name,
 * writes the bytes into a directory that is served publicly, and returns a string; from that moment
 * the only thing that remembered the file was whichever course or banner column happened to hold
 * the string. Deletion therefore had nothing to reason about except the string itself — and a URL
 * in a request body is a claim the caller made, not evidence of anything.
 *
 * <p>What a row asserts is narrow and literal: <em>this account put these bytes here</em>. It says
 * nothing about who may view the file (uploads are public, and stay public), nothing about who may
 * reference it, and nothing about how many things do. It answers exactly one question, for exactly
 * one caller — {@link UploadRetentionService} — and that question is whether a particular account's
 * action may cause a particular file to be destroyed.
 *
 * <h2>Absence is the whole point</h2>
 * There is no row for anything uploaded before the table existed, and the migration deliberately
 * invents none. So "no row" means "we do not know who uploaded this", and the retention rule reads
 * that as a refusal rather than as a licence: an owner-unknown file is never deleted automatically.
 * That is why {@code uploaderUserId} is non-null — a nullable owner would collapse "nobody owns it"
 * and "we never knew" into one value, and one of the two would eventually be read as permission.
 *
 * <p>The uploader is stored as a plain user id rather than as a {@code @ManyToOne User}. The
 * comparison this record exists for is an identity check against the caller's id, it is made
 * outside any session that has the user loaded, and keeping it a scalar means recording an upload
 * cannot drag a user graph — or a lazy-loading failure — into the upload path.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "uploads",
        uniqueConstraints = @UniqueConstraint(name = "uk_uploads_stored_name", columnNames = "stored_name"),
        indexes = @Index(name = "idx_uploads_uploader", columnList = "uploader_user_id"))
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The generated {@code <uuid>.<ext>} the bytes live under — not the served URL.
     *
     * <p>The name is the file; {@code /uploads/} is a route, and a route can be re-mounted or
     * proxied without any of these bytes moving. Storing the name keeps the lookup keyed on the
     * identity of the file rather than on a piece of URL construction.
     */
    @Column(name = "stored_name", nullable = false, updatable = false)
    private String storedName;

    /** The account that stored the bytes. Never inferred from anything that references them. */
    @Column(name = "uploader_user_id", nullable = false, updatable = false)
    private Long uploaderUserId;

    /** Kept for inventory and support. Not a security control — the bytes were validated already. */
    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UploadedFile other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
