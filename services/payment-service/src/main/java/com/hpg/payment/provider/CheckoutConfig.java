package com.hpg.payment.provider;

import java.util.Map;

/**
 * @param scriptUrl provider checkout script to load (null for the simulated provider)
 * @param options   options object passed to the provider's checkout script, serialised to JSON
 * @param simulated true when the simulated provider is active; the page then shows simulation buttons
 */
public record CheckoutConfig(String scriptUrl, Map<String, Object> options, boolean simulated) {
}
