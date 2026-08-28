package com.manara.backend.course.dto;

import com.manara.backend.course.model.ContentEntityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Something that was part of the course when this learner enrolled and is not part of it now.
 *
 * <p>It cannot be a row in the curriculum, because there is nothing left to open — so it is listed
 * separately, at course level. Without it a learner's course simply loses a lesson between two
 * visits, with the progress bar moving for no visible reason.
 *
 * <p>The name is the one the change log snapshotted at deletion. There is nothing else left to read
 * it from.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RemovedContentResponse {

    private ContentEntityType entityType;
    private String title;
    private String summary;
    private LocalDateTime at;
}
