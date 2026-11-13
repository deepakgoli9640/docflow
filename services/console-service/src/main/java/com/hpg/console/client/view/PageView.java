package com.hpg.console.client.view;

import java.util.List;

/** The APIs' paginated shape, as the templates need it. */
public record PageView<T>(List<T> content, PageMeta page) {

    public record PageMeta(int number, int size, long totalElements, int totalPages) {
    }

    public boolean hasPrevious() {
        return page != null && page.number() > 0;
    }

    public boolean hasNext() {
        return page != null && page.number() + 1 < page.totalPages();
    }

    public int nextPage() {
        return page.number() + 1;
    }

    public int previousPage() {
        return page.number() - 1;
    }

    public boolean isEmpty() {
        return content == null || content.isEmpty();
    }
}
