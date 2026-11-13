package com.hpg.refund.application;

import com.hpg.common.error.NotFoundException;
import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundErrorCode;
import com.hpg.refund.domain.RefundStatus;
import com.hpg.refund.domain.RefundStatusHistory;
import com.hpg.refund.infrastructure.persistence.RefundRepository;
import com.hpg.refund.infrastructure.persistence.RefundStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class RefundQueryService {

    private final RefundRepository refunds;
    private final RefundStatusHistoryRepository history;

    public RefundQueryService(RefundRepository refunds, RefundStatusHistoryRepository history) {
        this.refunds = refunds;
        this.history = history;
    }

    public record RefundDetails(Refund refund, List<RefundStatusHistory> history) {
    }

    /** Not found and "belongs to another merchant" are the same 404. */
    public RefundDetails get(UUID merchantId, UUID refundId) {
        Refund refund = refunds.findByIdAndMerchantId(refundId, merchantId)
                .orElseThrow(() -> new NotFoundException(RefundErrorCode.REFUND_NOT_FOUND, "Refund " + refundId + " not found"));
        return new RefundDetails(refund, history.findByRefundIdOrderByIdAsc(refundId));
    }

    public Page<Refund> list(UUID merchantId, UUID paymentId, RefundStatus status, Pageable pageable) {
        Specification<Refund> spec = (root, q, cb) -> cb.equal(root.get("merchantId"), merchantId);
        if (paymentId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("paymentId"), paymentId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return refunds.findAll(spec, pageable);
    }
}
