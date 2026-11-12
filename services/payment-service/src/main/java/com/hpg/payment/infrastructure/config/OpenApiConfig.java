package com.hpg.payment.infrastructure.config;

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
        info = @Info(title = "HPG Payment Service", version = "v1",
                description = "Payment initiation, status, capture and cancellation. All POSTs require Idempotency-Key."),
        security = @SecurityRequirement(name = "oauth2"))
@SecurityScheme(name = "oauth2", type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(clientCredentials = @OAuthFlow(tokenUrl = "/oauth2/token")))
public class OpenApiConfig {
}
