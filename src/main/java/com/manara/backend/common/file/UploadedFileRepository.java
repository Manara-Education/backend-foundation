package com.manara.backend.common.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The upload-ownership registry.
 *
 * <p>One lookup carries the whole point of the table: given the name a file is stored under, who
 * put it there. An empty {@link Optional} is a meaningful answer and the common one for anything
 * uploaded before the record existed — it means "unknown", and every caller must treat it as a
 * refusal to act rather than as an absence of objection.
 */
@Repository
public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {

    Optional<UploadedFile> findByStoredName(String storedName);
}
