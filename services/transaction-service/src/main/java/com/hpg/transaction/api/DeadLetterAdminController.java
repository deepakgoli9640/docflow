package com.hpg.transaction.api;

import com.hpg.common.error.BusinessException;
import com.hpg.messaging.deadletter.DeadLetterService;
import com.hpg.transaction.domain.TransactionErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operations: events that failed every retry in some consumer, and replaying them once the cause is fixed.
 * Served here for the whole platform (dead-letter topics are shared); consumers deduplicate by eventId, so a
 * replay only affects the consumer that failed.
 */
@RestController
@Tag(name = "Dead letters (OPS)", description = "Inspect and replay events that failed processing")
public class DeadLetterAdminController {

    private final DeadLetterService deadLetters;

    public DeadLetterAdminController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping("/api/v1/admin/dead-letters")
    @Operation(summary = "Newest dead letters", description = "Optional sourceTopic, e.g. payment.events.v1. Each entry "
            + "names the consumer group that failed, the exception and whether it was replayed.")
    public List<DeadLetterService.DeadLetter> list(@RequestParam(required = false) String sourceTopic,
            @RequestParam(defaultValue = "50") int limit) {
        if (sourceTopic != null && !sourceTopic.isBlank() && !DeadLetterService.sourceTopics().contains(sourceTopic)) {
            throw new BusinessException(TransactionErrorCode.DEAD_LETTER_NOT_FOUND, "Unknown topic " + sourceTopic);
        }
        return deadLetters.list(sourceTopic, Math.max(1, Math.min(limit, 200)));
    }

    @PostMapping("/api/v1/admin/dead-letters/{dltTopic}/{partition}/{offset}/replay")
    @Operation(summary = "Replay one dead letter to its original topic",
            description = "409 if it was replayed before (force=true to replay again).")
    public ResponseEntity<DeadLetterService.DeadLetter> replay(@PathVariable String dltTopic, @PathVariable int partition,
            @PathVariable long offset, @RequestParam(defaultValue = "false") boolean force, Authentication who) {
        try {
            return ResponseEntity.accepted().body(deadLetters.replay(dltTopic, partition, offset, who.getName(), force));
        } catch (DeadLetterService.NotFoundException e) {
            throw new BusinessException(TransactionErrorCode.DEAD_LETTER_NOT_FOUND, e.getMessage());
        } catch (DeadLetterService.AlreadyReplayedException e) {
            throw new BusinessException(TransactionErrorCode.DEAD_LETTER_ALREADY_REPLAYED, e.getMessage());
        }
    }
}
