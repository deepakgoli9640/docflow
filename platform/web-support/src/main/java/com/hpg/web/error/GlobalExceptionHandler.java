package com.hpg.web.error;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.common.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns every exception into an RFC 9457 Problem Details response.
 *
 * <p>Rules:
 * <ul>
 *   <li>Expected failures ({@link BusinessException}) keep their code and message.</li>
 *   <li>Infrastructure failures map to generic codes (409 / 503) and are logged.</li>
 *   <li>Anything unexpected is logged with its stack trace and returned as a generic 500 — never with
 *       SQL, class names or stack traces in the body. The {@code correlationId} in the body links the
 *       client's report to the log line.</li>
 * </ul>
 * Exceptions are never swallowed: every path either logs or returns a deliberate, documented response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("Business exception with server status: {}", code.code(), ex);
        } else {
            log.info("Request rejected: {} - {}", code.code(), ex.getMessage());
        }
        return respond(ProblemDetails.of(code, ex.getMessage(), ex.properties()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return respond(ProblemDetails.of(CommonErrorCode.VALIDATION_FAILED, "One or more fields are invalid",
                Map.of("violations", violations)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        log.info("Access denied: {}", ex.getMessage());
        return respond(ProblemDetails.of(CommonErrorCode.FORBIDDEN, "You are not allowed to perform this operation"));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return respond(ProblemDetails.of(CommonErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by another request. Re-read it and retry."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        // Expected uniqueness violations are translated by the services into specific codes. Reaching
        // this handler means an unanticipated constraint fired: log it fully, answer generically.
        log.warn("Unhandled data integrity violation", ex);
        return respond(ProblemDetails.of(CommonErrorCode.CONFLICT,
                "The request conflicts with existing data."));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable", ex);
        ResponseEntity<ProblemDetail> response = respond(ProblemDetails.of(CommonErrorCode.SERVICE_UNAVAILABLE,
                "The service is temporarily unavailable. Retry later with the same Idempotency-Key."));
        return ResponseEntity.status(response.getStatusCode()).header(HttpHeaders.RETRY_AFTER, "5")
                .body(response.getBody());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (isConnectionFailure(ex)) {
            // A pooled connection that died with the database surfaces as whatever the failing call threw
            // (e.g. JpaSystemException "Unable to rollback"): still an outage, not a bug.
            return handleDatabaseUnavailable(ex);
        }
        log.error("Unexpected error", ex);
        return respond(ProblemDetails.of(CommonErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the correlationId when contacting support."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        ProblemDetail problem = ProblemDetails.of(CommonErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid", Map.of("violations", violations));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = ProblemDetails.of(CommonErrorCode.VALIDATION_FAILED,
                "One or more parameters are invalid", Map.of("violations", violations));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Do not echo the parser message: it can contain fragments of the request body.
        log.info("Malformed request body: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        ProblemDetail problem = ProblemDetails.of(CommonErrorCode.MALFORMED_REQUEST,
                "The request body is not valid JSON or has fields of the wrong type.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Enriches the ProblemDetail that Spring MVC builds for its own exceptions (404, 405, 415, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Spring passes body == null for its own exceptions and builds the ProblemDetail in super.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("errorCode"))) {
            ProblemDetails.decorate(problem, codeFor(statusCode));
        }
        return response;
    }

    private static CommonErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> CommonErrorCode.VALIDATION_FAILED;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? CommonErrorCode.CONFLICT : CommonErrorCode.INTERNAL_ERROR;
        };
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * True if the cause chain shows the database (or another dependency) could not be reached: JDBC SQLState
     * class 08 (connection exception), a transient/recoverable connection SQLException, or a socket error.
     */
    static boolean isConnectionFailure(Throwable ex) {
        int depth = 0;
        for (Throwable t = ex; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof java.sql.SQLNonTransientConnectionException
                    || t instanceof java.sql.SQLRecoverableException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
            // HikariCP evicts a connection after a fatal (08xxx) error and closes its proxy; the transaction's
            // rollback then fails with "Connection is closed" (no SQLState) and Spring reports that instead of
            // the original error. Seen in DatabaseDownIT.
            if (t instanceof java.sql.SQLException sql && "Connection is closed".equals(sql.getMessage())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
