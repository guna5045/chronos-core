package com.chronoscore.coordinator.api.dto.response;

import java.util.List;

/**
 * Standard paginated container response for collections.
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static <T> PagedResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        boolean hasNext = (long) (page + 1) * size < totalElements;
        return new PagedResponse<>(
                items != null ? items : List.of(),
                page,
                size,
                totalElements,
                totalPages,
                hasNext
        );
    }
}
