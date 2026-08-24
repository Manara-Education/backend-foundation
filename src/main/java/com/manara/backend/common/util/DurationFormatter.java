package com.manara.backend.common.util;

import com.manara.backend.common.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DurationFormatter {

    private static final String KEY_HOURS = "duration.hours";
    private static final String KEY_MINUTES = "duration.minutes";
    private static final String KEY_SECONDS = "duration.seconds";
    private static final String KEY_ZERO = "duration.zero";

    private final MessageService messageService;

    public String formatSeconds(Integer totalSeconds) {
        if (totalSeconds == null || totalSeconds <= 0) {
            return messageService.get(KEY_ZERO);
        }

        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(messageService.get(KEY_HOURS, hours)).append(' ');
        if (minutes > 0) sb.append(messageService.get(KEY_MINUTES, minutes)).append(' ');
        if (seconds > 0 || sb.isEmpty()) sb.append(messageService.get(KEY_SECONDS, seconds));

        return sb.toString().trim();
    }
}