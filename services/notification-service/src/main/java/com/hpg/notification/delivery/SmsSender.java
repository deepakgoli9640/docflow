package com.hpg.notification.delivery;

/**
 * Port for an SMS provider (Twilio, MSG91, ...). No real provider is integrated: the brief asks for the
 * abstraction, and this keeps the seam where a provider adapter would go without pretending one exists.
 */
public interface SmsSender {

    /** @throws RuntimeException if the message could not be handed to the provider */
    void send(String phoneNumber, String message);

    /** Whether this implementation actually sends anything, used in health and status reporting. */
    boolean isSimulated();
}
