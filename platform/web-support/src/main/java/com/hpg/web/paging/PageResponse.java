package com.hpg.web.paging;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Stable JSON shape for paginated responses. Spring Data's {@code Page} is not serialised directly
 * because its JSON layout is an implementation detail that has changed between versions.
 */
public record PageResponse<T>(List<T> content, PageMetadata page) {

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                new PageMetadata(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }
}
