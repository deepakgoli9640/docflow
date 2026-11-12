package com.hpg.merchant.application;

public record RegisterMerchantCommand(String legalName, String displayName, String contactEmail, String contactPhone) {
}
