package com.hpg.merchant.infrastructure.persistence;

import com.hpg.merchant.domain.MerchantConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantConfigurationRepository extends JpaRepository<MerchantConfiguration, UUID> {
}
