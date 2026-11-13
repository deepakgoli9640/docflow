package com.hpg.transaction.infrastructure.persistence;

import com.hpg.transaction.domain.Transaction;
import com.hpg.transaction.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByPaymentIdAndType(UUID paymentId, TransactionType type);

    Optional<Transaction> findByRefundId(UUID refundId);

    Optional<Transaction> findByTransactionRefAndMerchantId(String transactionRef, UUID merchantId);
}
