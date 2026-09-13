package com.manara.backend.common.file;

import com.manara.backend.banner.repository.BannerRepository;
import com.manara.backend.course.repository.CourseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Whether a stored file may be destroyed, and when.
 *
 * <p>Replacing a course cover used to delete the previous one outright. The only thing that
 * decision rested on was that the payload named a different, non-null image — so the URL sitting in
 * the course's {@code image} column was, in effect, a delete capability for whatever file it named.
 * Since {@code /uploads/**} is served publicly, that URL is discoverable, and the column is written
 * from an unvalidated client string: an instructor could point their own course at somebody else's
 * cover, save again, and the second save destroyed a file they had nothing to do with. Every
 * ownership check on the course itself passed, correctly, because none of them was ever about the
 * file.
 *
 * <h2>The four questions</h2>
 * A file is removed only when all four are answered yes, and the answer to any of them being "we
 * cannot tell" counts as no:
 *
 * <ol>
 *   <li><strong>Is it ours?</strong> The URL must resolve to a stored name under {@code /uploads/}.
 *       An external cover is not this mechanism's business at all.
 *   <li><strong>Do we know who uploaded it?</strong> There must be an ownership record. Everything
 *       stored before that record existed has none, and stays undeletable forever — the alternative
 *       is inferring an owner from a reference, which is the very inference that caused the defect.
 *   <li><strong>Is the caller that person?</strong> Attaching a file to your own course does not
 *       make it yours. Ownership is the recorded uploader and nothing else, so the attack path ends
 *       here: the file the attacker attached is still owned by the person who uploaded it.
 *   <li><strong>Is anything still using it?</strong> Counted across every course cover and every
 *       banner image at the moment of deletion, after the edit that retired this reference has
 *       committed. A file two courses show survives either of them dropping it.
 * </ol>
 *
 * <h2>After the commit, not before it</h2>
 * The old call ran inside {@code updateCourse}'s transaction, after a flush but before the commit,
 * so a transaction that failed afterwards left the database unchanged and the file gone — an
 * unwritable outcome to recover from, since nothing records what the bytes were. Deletion is
 * therefore registered on the transaction and runs from {@code afterCommit}: no commit, no
 * deletion. The reference count is re-read there rather than reused from inside the transaction,
 * so a course that attached the file while the edit was in flight is seen.
 *
 * <p>That last re-read narrows the window between "nothing references this" and the unlink; it
 * cannot close it, because a reference committed in the microseconds after the count is still
 * possible. The residual risk is deliberately accepted rather than papered over with locking that
 * would put a filesystem operation on a course-save's critical path: the outcome is a broken image
 * on a course that reused somebody else's asset in that instant, and the cure for the whole class
 * is the attachment-side rule tracked separately, not a bigger lock here.
 *
 * <h2>Failure direction</h2>
 * Everything here fails towards keeping the file. Unknown owner, unreadable URL, a database error
 * while checking — all of them return without deleting. The cost of that is orphaned bytes on a
 * disk; the cost of the opposite is somebody else's course cover, permanently.
 */
@Slf4j
@Service
public class UploadRetentionService {

    private final UploadOwnershipRegistry uploadOwnershipRegistry;
    private final CourseRepository courseRepository;
    private final BannerRepository bannerRepository;
    private final FileUploadService fileUploadService;

    /**
     * A transaction of this rule's own, and it has to be its own.
     *
     * <p>The decision runs from {@code afterCommit}, where the caller's transaction has committed
     * but its resources are still bound to the thread. Anything that merely joins "the current
     * transaction" there attaches to a connection that has already committed and will never commit
     * again: the reads answer from a settled snapshot and the ownership row this deletes stays put,
     * silently. Suspending it and starting a genuinely new one is what makes the re-read see the
     * state the commit produced — including a reference some other request added while this edit
     * was in flight — and what makes the record removal stick.
     */
    private final TransactionTemplate afterCommitTransaction;

    public UploadRetentionService(UploadOwnershipRegistry uploadOwnershipRegistry,
                                  CourseRepository courseRepository,
                                  BannerRepository bannerRepository,
                                  FileUploadService fileUploadService,
                                  PlatformTransactionManager transactionManager) {
        this.uploadOwnershipRegistry = uploadOwnershipRegistry;
        this.courseRepository = courseRepository;
        this.bannerRepository = bannerRepository;
        this.fileUploadService = fileUploadService;
        this.afterCommitTransaction = new TransactionTemplate(transactionManager);
        this.afterCommitTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Asks for a file to be released once the current transaction commits — and only then.
     *
     * <p>The caller is saying "this reference is going away", not "delete this file". Whether
     * anything is actually deleted is decided later, by {@link #release}, against the state the
     * commit produced.
     *
     * <p>With no transaction in progress the release runs immediately, which is what a caller
     * outside a transaction means and what keeps this callable from a test or a future maintenance
     * job without a transaction being simulated around it.
     *
     * @param fileUrl         the URL that is no longer referenced by the caller's entity
     * @param requesterUserId the account whose action retired the reference
     */
    public void releaseWhenCommitted(String fileUrl, Long requesterUserId) {
        if (fileUrl == null || requesterUserId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release(fileUrl, requesterUserId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                release(fileUrl, requesterUserId);
            }
        });
    }

    /**
     * The decision itself, made at the moment of deletion.
     *
     * <p>Never throws. It runs from a transaction-synchronization callback, where an exception
     * would surface to the caller of a transaction that has already committed successfully — a
     * failed cover replacement reported for an edit that in fact succeeded. A file that cannot be
     * released is a file that stays on disk, which is the harmless outcome.
     */
    public void release(String fileUrl, Long requesterUserId) {
        try {
            afterCommitTransaction.executeWithoutResult(status -> decide(fileUrl, requesterUserId));
        } catch (RuntimeException ex) {
            log.warn("Upload retention check failed; the file is kept", ex);
        }
    }

    /** The four questions, asked inside a transaction that can actually see the committed state. */
    private void decide(String fileUrl, Long requesterUserId) {
        Optional<UploadedFile> owner = uploadOwnershipRegistry.ownerOf(fileUrl);
        if (owner.isEmpty()) {
            // Two cases, one answer. Either this is not one of our uploads — an external cover URL,
            // which we have no business touching — or it is a file whose uploader was never
            // recorded. Neither is permission.
            log.debug("Upload not released: no ownership on record");
            return;
        }

        UploadedFile uploaded = owner.get();
        if (!uploaded.getUploaderUserId().equals(requesterUserId)) {
            // The interesting refusal: the caller is retiring a reference to a file somebody else
            // uploaded. Legitimate whenever an instructor pointed a course at a public asset;
            // either way the file is not theirs to destroy.
            log.info("Upload not released: retired by userId={} but uploaded by userId={}",
                    requesterUserId, uploaded.getUploaderUserId());
            return;
        }

        long remaining = remainingReferences(fileUrl);
        if (remaining > 0) {
            log.debug("Upload not released: {} reference(s) remain", remaining);
            return;
        }

        // The file first, the record second. If the record went first and the unlink then failed,
        // the bytes would be left behind with nobody on record as their owner — which is to say
        // undeletable forever. This order's worst case is a record describing a file that is
        // already gone, which the next release attempt tidies up harmlessly.
        fileUploadService.deleteFile(fileUrl);
        uploadOwnershipRegistry.forget(uploaded.getId());
        log.info("Upload released by its uploader: userId={}", requesterUserId);
    }

    /**
     * How many stored references to this URL still exist, across every holder of one.
     *
     * <p>Courses and banners are both counted because both store an upload URL, and only one of
     * them ever deleted anything — which is precisely how a banner's image could be destroyed by a
     * course edit. A holder added in future must be counted here too, or this method starts
     * answering a narrower question than its name promises.
     */
    private long remainingReferences(String fileUrl) {
        return courseRepository.countByImage(fileUrl) + bannerRepository.countByImageUrl(fileUrl);
    }
}
