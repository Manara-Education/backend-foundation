package com.manara.backend.banner.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * The management list's new order, as the ids in the order they should run.
 *
 * <p>Positions rather than priorities: dragging a row changes where everything below it sits too,
 * so sending one banner's new number would leave the client to recompute the rest and the two sides
 * to disagree about the result.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BannerOrderRequest {

    @NotEmpty(message = "{validation.banner.order.required}")
    private List<Long> bannerIds;
}
