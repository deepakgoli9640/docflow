package com.hpg.notification.delivery;

import com.hpg.common.masking.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>SIMULATED</b> SMS sender: writes a log line instead of sending anything. It exists so the SMS path
 * is exercised end to end without an account at a provider. Every line says SIMULATED, and the phone
 * number is masked, so nobody can mistake a log for a delivered message.
 */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    public LoggingSmsSender() {
        log.warn("*** SIMULATED SMS SENDER ACTIVE: no text messages are sent. ***");
    }

    @Override
    public void send(String phoneNumber, String message) {
        log.info("SIMULATED SMS to {}: {} characters", Masking.phone(phoneNumber), message.length());
    }

    @Override
    public boolean isSimulated() {
        return true;
    }
}
