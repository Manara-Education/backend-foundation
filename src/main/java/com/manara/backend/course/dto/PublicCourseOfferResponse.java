package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manara.backend.course.model.CourseAccessType;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a public course costs, and whether that can be stated at all.
 *
 * <p>Every field is always present. An absent amount is an explicit {@code null} beside a
 * {@link PublicPricingStatus} that says why, so a client never has to guess whether a missing
 * number means "free", "unknown" or "not sent".
 *
 * <table>
 *   <caption>Which fields are populated</caption>
 *   <tr><th>accessType</th><th>pricingStatus</th><th>currency</th><th>purchasePrice</th><th>plans</th></tr>
 *   <tr><td>FREE</td><td>FREE</td><td>null</td><td>null</td><td>[]</td></tr>
 *   <tr><td>PURCHASE</td><td>PRICED</td><td>EGP</td><td>&gt; 0</td><td>[]</td></tr>
 *   <tr><td>PURCHASE</td><td>UNAVAILABLE</td><td>EGP</td><td>null</td><td>[]</td></tr>
 *   <tr><td>SUBSCRIPTION</td><td>PRICED</td><td>EGP</td><td>null</td><td>one or more</td></tr>
 *   <tr><td>SUBSCRIPTION</td><td>UNAVAILABLE</td><td>EGP</td><td>null</td><td>[]</td></tr>
 * </table>
 *
 * @param accessType    how the course is sold — the only thing that makes it free
 * @param pricingStatus whether a truthful price can be stated
 * @param currency      {@code "EGP"} for a paid course, {@code null} for a free one
 * @param purchasePrice the one-off price in EGP, two decimal places, for a priced purchase only
 * @param plans         the sellable plans of a priced subscription, in the instructor's order
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicCourseOfferResponse(
        CourseAccessType accessType,
        PublicPricingStatus pricingStatus,
        String currency,
        BigDecimal purchasePrice,
        List<PublicSubscriptionPlanResponse> plans) {
}
