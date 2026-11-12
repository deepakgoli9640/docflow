package com.hpg.web.paging;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.CommonErrorCode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.Set;

/**
 * Rejects sorting by fields that are not explicitly allowed.
 *
 * <p>Without this, a client can sort by any entity property — including unindexed ones (slow full
 * scans) or properties that reveal internals.
 */
public final class SortValidator {

    private SortValidator() {
    }

    public static void requireAllowed(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new BusinessException(CommonErrorCode.INVALID_SORT_FIELD,
                        "Cannot sort by '" + order.getProperty() + "'.",
                        Map.of("allowedSortFields", allowed.stream().sorted().toList()));
            }
        }
    }
}
