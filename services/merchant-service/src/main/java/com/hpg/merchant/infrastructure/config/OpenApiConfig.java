package com.hpg.merchant.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "HPG Merchant Service", version = "v1",
                description = "Hospital onboarding, configuration, API keys and token issuance."),
        security = @SecurityRequirement(name = "oauth2"))
@SecurityScheme(name = "oauth2", type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(clientCredentials = @OAuthFlow(tokenUrl = "/oauth2/token")))
public class OpenApiConfig {
}
