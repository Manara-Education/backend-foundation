package com.manara.backend.contact.dto;

import java.util.Set;

/**
 * The fixed set of inquiry topics the contact form's {@code <select>} offers.
 *
 * <p>Arabic display text, not a code — the frontend has no separate locale to translate it into
 * and nothing else reads this value, so there is nothing a code would buy. Kept as a whitelist
 * rather than free text because the value is echoed into the notification email's subject line.
 */
public final class ContactTopic {

    public static final String GENERAL = "استفسار عام";
    public static final String COURSES = "الدورات";
    public static final String ACCOUNT = "الحساب";
    public static final String PAYMENTS = "المدفوعات";
    public static final String TECHNICAL = "مشكلة تقنية";

    public static final Set<String> DISPLAY_VALUES = Set.of(GENERAL, COURSES, ACCOUNT, PAYMENTS, TECHNICAL);

    private ContactTopic() {
    }
}
