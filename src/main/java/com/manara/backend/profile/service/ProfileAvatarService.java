package com.manara.backend.profile.service;

import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.file.FileUploadService;
import com.manara.backend.common.file.UploadOwnershipRegistry;
import com.manara.backend.common.file.UploadRetentionService;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.mapper.ProfileMapper;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The signed-in account's own profile photo, and nothing else's.
 *
 * <p>The order of the replacement is the point of this class. The new file is written and recorded
 * first, outside any transaction; only then is the account row pointed at it, under a row lock; and
 * the previous file is released only after that commit. So a photo that fails to decode or to store
 * leaves the old one in place and untouched, a database failure after the store removes the new file
 * again, and the old file is never deleted while the row might still name it.
 *
 * <p>The URL written to the row is always one {@link FileUploadService#storeAvatar} returned in this
 * request. A client cannot point its photo at an arbitrary URL, including another account's upload.
 */
@Slf4j
@Service
public class ProfileAvatarService {

    private final FileUploadService fileUploadService;
    private final UploadOwnershipRegistry uploadOwnershipRegistry;
    private final UploadRetentionService uploadRetentionService;
    private final UserRepository userRepository;
    private final ProfileMapper profileMapper;
    private final TransactionTemplate transaction;

    public ProfileAvatarService(FileUploadService fileUploadService,
                                UploadOwnershipRegistry uploadOwnershipRegistry,
                                UploadRetentionService uploadRetentionService,
                                UserRepository userRepository,
                                ProfileMapper profileMapper,
                                PlatformTransactionManager transactionManager) {
        this.fileUploadService = fileUploadService;
        this.uploadOwnershipRegistry = uploadOwnershipRegistry;
        this.uploadRetentionService = uploadRetentionService;
        this.userRepository = userRepository;
        this.profileMapper = profileMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ProfileResponse replaceAvatar(User principal, MultipartFile file) {
        Long userId = principal.getId();
        String storedUrl = fileUploadService.storeAvatar(file);
        try {
            // Recorded as the caller's, so releasing it later is permitted to the caller alone.
            uploadOwnershipRegistry.record(storedUrl, userId, contentTypeOf(storedUrl), null);
            return transaction.execute(status -> {
                User account = lockedAccount(userId);
                String previousUrl = account.getAvatarUrl();
                account.setAvatarUrl(storedUrl);
                uploadRetentionService.releaseWhenCommitted(previousUrl, userId);
                return profileMapper.toProfileResponse(account);
            });
        } catch (RuntimeException failure) {
            discard(storedUrl);
            throw failure;
        }
    }

    /** Idempotent: an account with no photo is answered as it is. */
    public ProfileResponse removeAvatar(User principal) {
        Long userId = principal.getId();
        return transaction.execute(status -> {
            User account = lockedAccount(userId);
            String previousUrl = account.getAvatarUrl();
            if (previousUrl != null) {
                account.setAvatarUrl(null);
                uploadRetentionService.releaseWhenCommitted(previousUrl, userId);
            }
            return profileMapper.toProfileResponse(account);
        });
    }

    private User lockedAccount(Long userId) {
        return userRepository.findForUpdateById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFound"));
    }

    /** The new file never became the photo: remove it and its ownership record. */
    private void discard(String storedUrl) {
        try {
            fileUploadService.deleteFile(storedUrl);
            uploadOwnershipRegistry.ownerOf(storedUrl)
                    .ifPresent(record -> uploadOwnershipRegistry.forget(record.getId()));
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not discard an avatar that was never applied", cleanupFailure);
        }
    }

    /** The stored file's type follows the extension the re-encoder chose, never the request. */
    private static String contentTypeOf(String storedUrl) {
        return storedUrl.endsWith(".jpg") ? "image/jpeg" : "image/png";
    }
}
