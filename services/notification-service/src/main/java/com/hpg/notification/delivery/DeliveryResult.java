package com.hpg.notification.delivery;

/**
 * @param permanent true when retrying cannot help (e.g. no webhook URL configured)
 */
public record DeliveryResult(boolean success, Integer httpStatus, String error, boolean permanent) {

    public static DeliveryResult ok(Integer httpStatus) {
        return new DeliveryResult(true, httpStatus, null, false);
    }

    public static DeliveryResult retry(Integer httpStatus, String error) {
        return new DeliveryResult(false, httpStatus, error, false);
    }

    public static DeliveryResult skip(String reason) {
        return new DeliveryResult(false, null, reason, true);
    }
}
