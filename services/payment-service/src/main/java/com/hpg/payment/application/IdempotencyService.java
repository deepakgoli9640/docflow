package com.hpg.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hpg.common.error.BusinessException;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.payment.domain.PaymentErrorCode;
import com.hpg.payment.infrastructure.persistence.IdempotencyStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotency-Key handling (docs/02-payment-lifecycle.md §6.1).
 *
 * <ul>
 *   <li>The key is stored in the SAME transaction as the change it protects, so a key never exists
 *       without its effect, nor an effect without its key.</li>
 *   <li>A repeat with the same body returns the current state of the resource ("replay").</li>
 *   <li>A repeat with a different body is a client bug and gets 422.</li>
 * </ul>
 * Postgres, not Redis: the key and the business row must commit or roll back together.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final IdempotencyStore store;
    private final PaymentProperties properties;
    private final Clock clock;
    private final ObjectMapper canonical;

    public IdempotencyService(IdempotencyStore store, PaymentProperties properties, Clock clock, ObjectMapper mapper) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        // Canonical JSON (sorted keys) so semantically identical bodies hash the same.
        this.canonical = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .findAndAddModules()
                .build();
    }

    public record Replay(UUID resourceId, int responseStatus) {
    }

    public void requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(CommonErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "Send a unique Idempotency-Key header (e.g. a UUID) with every request that changes state.");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new BusinessException(PaymentErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be 8-64 characters of letters, digits, '-' or '_'.");
        }
    }

    public String hash(Object request) {
        try {
            byte[] bytes = canonical.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash request", e);
        }
    }

    /** @return a replay if this key was already used with the same request; throws if with a different one */
    public Optional<Replay> findReplay(UUID merchantId, String operation, String key, String requestHash) {
        return store.find(merchantId, operation, key).map(stored -> {
            if (!stored.requestHash().equals(requestHash)) {
                throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used with a different request body.",
                        Map.of("idempotencyKey", key));
            }
            return new Replay(stored.resourceId(), stored.responseStatus());
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(UUID merchantId, String operation, String key, String requestHash, UUID resourceId,
            int responseStatus) {
        var now = clock.instant();
        return store.insert(merchantId, operation, key, requestHash, resourceId, responseStatus, now,
                now.plus(properties.idempotencyTtl()));
    }
}
