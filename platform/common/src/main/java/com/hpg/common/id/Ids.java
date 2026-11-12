package com.hpg.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates UUID version 7 identifiers (RFC 9562).
 *
 * <p>Why v7 and not {@link UUID#randomUUID()} (v4): v7 starts with a millisecond timestamp, so new
 * IDs are roughly increasing. B-tree primary-key indexes then append at the right edge instead of
 * inserting at random pages, which keeps inserts fast and indexes compact. IDs are generated in the
 * application (not the database) so an entity has its ID before it is saved — useful for outbox
 * events, logs and idempotency records written in the same transaction.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        long millis = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) (millis >>> (40 - 8 * i));
        }
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70); // version 7
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80); // IETF variant
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }
}
