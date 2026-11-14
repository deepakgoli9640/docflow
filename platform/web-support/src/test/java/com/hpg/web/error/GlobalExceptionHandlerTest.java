package com.hpg.web.error;

import com.hpg.common.correlation.CorrelationId;
import com.hpg.common.error.BusinessException;
import com.hpg.common.error.ErrorCode;
import com.hpg.web.correlation.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    enum TestError implements ErrorCode {
        REFUND_EXCEEDS_CAPTURED_AMOUNT;

        public String code() { return name(); }
        public int httpStatus() { return 422; }
        public String title() { return "Refund exceeds refundable amount"; }
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class TestController {
        @GetMapping("/business")
        void business() {
            throw new BusinessException(TestError.REFUND_EXCEEDS_CAPTURED_AMOUNT, "Only 15000.00 INR remains",
                    Map.of("remaining", "15000.00"));
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("SELECT * FROM secret_table failed");
        }

        @GetMapping("/db-down")
        void dbDown() {
            throw new CannotCreateTransactionException("Connection refused");
        }

        @GetMapping("/db-connection-lost")
        void dbConnectionLost() {
            throw new IllegalStateException("Unable to rollback against JDBC Connection",
                    new java.sql.SQLException("Connection is closed"));
        }

        @GetMapping("/db-io-error")
        void dbIoError() {
            throw new IllegalStateException("query failed",
                    new java.sql.SQLException("An I/O error occurred while sending to the backend.", "08006"));
        }

        @GetMapping("/statement-timeout")
        void statementTimeout() {
            throw new IllegalStateException("query failed",
                    new java.sql.SQLException("ERROR: canceling statement due to statement timeout", "57014"));
        }

        @GetMapping("/sql-syntax")
        void sqlSyntax() {
            throw new IllegalStateException("bad query", new java.sql.SQLException("syntax error", "42601"));
        }

        @GetMapping("/lock")
        void lock() {
            throw new OptimisticLockingFailureException("version mismatch");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Body body) {
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void businessExceptionBecomesProblemDetailWithCodeAndCorrelationId() throws Exception {
        mvc.perform(get("/business").header(CorrelationId.HEADER, "req-123"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(CorrelationId.HEADER, "req-123"))
                .andExpect(jsonPath("$.errorCode").value("REFUND_EXCEEDS_CAPTURED_AMOUNT"))
                .andExpect(jsonPath("$.title").value("Refund exceeds refundable amount"))
                .andExpect(jsonPath("$.detail").value("Only 15000.00 INR remains"))
                .andExpect(jsonPath("$.correlationId").value("req-123"))
                .andExpect(jsonPath("$.remaining").value("15000.00"))
                .andExpect(jsonPath("$.type").value("https://docs.hpg.example/errors/refund-exceeds-captured-amount"));
    }

    @Test
    void unexpectedExceptionDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", not(containsString("secret_table"))))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void databaseUnavailableIs503WithRetryAfter() throws Exception {
        mvc.perform(get("/db-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void brokenConnectionSurfacingAsAnotherExceptionIsStill503() throws Exception {
        // What a stopped database looked like in DatabaseDownIT: the rollback on a dead pooled connection failed.
        mvc.perform(get("/db-connection-lost"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void connectionExceptionSqlStateIs503() throws Exception {
        mvc.perform(get("/db-io-error")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void statementTimeoutIs503() throws Exception {
        mvc.perform(get("/statement-timeout")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void otherSqlErrorsStay500() throws Exception {
        mvc.perform(get("/sql-syntax")).andExpect(status().isInternalServerError());
    }

    @Test
    void optimisticLockIs409() throws Exception {
        mvc.perform(get("/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void beanValidationListsViolations() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("name"));
    }

    @Test
    void malformedJsonIs400WithoutEchoingBody() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void frameworkErrorsAreDecoratedToo() throws Exception {
        mvc.perform(post("/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        mvc.perform(get("/business").header(CorrelationId.HEADER, "bad value\nINJECTED"))
                .andExpect(header().string(CorrelationId.HEADER, not(containsString("INJECTED"))));
    }
}
