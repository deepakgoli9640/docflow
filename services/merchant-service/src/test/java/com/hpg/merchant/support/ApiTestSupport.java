package com.hpg.merchant.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small helpers so the integration tests read like the API flow they test. */
public final class ApiTestSupport {

    private final MockMvc mvc;
    private final ObjectMapper json;

    public ApiTestSupport(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public record RegisteredMerchant(UUID id, String keyId, String keySecret, String webhookSecret) {
    }

    public RegisteredMerchant register(String email) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "legalName", "City Hospital Pvt Ltd",
                "displayName", "City Hospital",
                "contactEmail", email,
                "contactPhone", "+919800001234"));
        MvcResult result = mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return new RegisteredMerchant(UUID.fromString(node.at("/merchant/id").asText()),
                node.at("/apiKey/keyId").asText(), node.at("/apiKey/keySecret").asText(),
                node.at("/webhookSecret").asText());
    }

    public String token(String clientId, String clientSecret) throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/token")
                        .with(httpBasic(clientId, clientSecret))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("access_token").asText();
    }

    public String opsToken() throws Exception {
        return token("ops-console", "test-ops-secret");
    }

    public String serviceToken() throws Exception {
        return token("notification-service", "test-notification-secret");
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
