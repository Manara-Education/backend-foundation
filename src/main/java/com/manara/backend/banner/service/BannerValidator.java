package com.manara.backend.banner.service;

import com.manara.backend.banner.dto.BannerRequest;
import com.manara.backend.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Every rule a banner payload has to satisfy that one annotation could not express.
 *
 * <p>These are the same rules the management form enforces before it submits. Repeating them here
 * is not redundancy: the form's copy is there so the owner is told which field is wrong while they
 * are still typing, and this copy is there because a request that never went through the form still
 * has to be held to them.
 */
@Component
public class BannerValidator {

    /**
     * A destination is either somewhere else on the web or somewhere on this site. Anything else —
     * {@code javascript:}, {@code data:} — is a script that would run from a control an instructor
     * put in front of every learner.
     */
    private static final Pattern ALLOWED_CTA_URL = Pattern.compile("^(https?://|#|/).+", Pattern.CASE_INSENSITIVE);

    public void validate(BannerRequest request) {
        boolean draft = Boolean.TRUE.equals(request.getDraft());

        validateCallToAction(request);
        validateWindow(request, draft);
        validatePriority(request);
    }

    /**
     * A button with a label and nowhere to go is the failure a learner notices — it renders, it
     * looks clickable, and it does nothing. A destination with no label is simply not rendered, so
     * it is left alone.
     */
    private void validateCallToAction(BannerRequest request) {
        boolean hasLabel = isNotBlank(request.getCallToActionLabel());
        boolean hasUrl = isNotBlank(request.getCallToActionUrl());

        if (hasLabel && !hasUrl) {
            throw new BusinessException("error.banner.ctaUrlRequired");
        }
        if (hasUrl && !ALLOWED_CTA_URL.matcher(request.getCallToActionUrl().trim()).matches()) {
            throw new BusinessException("error.banner.ctaUrlInvalid");
        }
    }

    /**
     * A draft may be saved half-scheduled — that is what a draft is for. Anything that is going to
     * be delivered has to say when it stops, or it runs until somebody remembers it.
     */
    private void validateWindow(BannerRequest request, boolean draft) {
        if (!draft && request.getEndAt() == null) {
            throw new BusinessException("error.banner.endRequired");
        }
        if (request.getStartAt() != null && request.getEndAt() != null
                && !request.getEndAt().isAfter(request.getStartAt())) {
            throw new BusinessException("error.banner.endBeforeStart");
        }
    }

    private void validatePriority(BannerRequest request) {
        if (request.getPriority() != null && request.getPriority() < 1) {
            throw new BusinessException("error.banner.priorityPositive");
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
