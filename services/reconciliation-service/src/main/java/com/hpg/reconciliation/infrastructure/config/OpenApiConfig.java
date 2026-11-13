package com.hpg.reconciliation.infrastructure.config;

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
        info = @Info(title = "HPG Reconciliation Service", version = "v1",
                description = "Daily reconciliation of the ledger against provider settlement reports, discrepancy workflow and reports (OPS)."),
        security = @SecurityRequirement(name = "oauth2"))
@SecurityScheme(name = "oauth2", type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(clientCredentials = @OAuthFlow(tokenUrl = "/oauth2/token")))
public class OpenApiConfig {
}
