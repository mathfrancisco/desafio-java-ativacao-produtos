package com.desafio.activation.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    private ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "client-a", "CUST-1", "INTERNET-500", NOW);
    }

    @Test
    void newRequestIsPendingAndDueImmediately() {
        var request = newRequest();

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.isClaimable(NOW)).isTrue();
        assertThat(request.getCreatedAt()).isEqualTo(NOW).isEqualTo(request.getUpdatedAt());
    }

    @Test
    void startProcessingIncrementsAttemptsAndTakesLease() {
        var request = newRequest();

        request.startProcessing(NOW, LEASE);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getLockedUntil()).isEqualTo(NOW.plus(LEASE));
        assertThat(request.isClaimable(NOW.plusSeconds(10))).isFalse();
        assertThat(request.isProcessingAttempt(1)).isTrue();
    }

    @Test
    void processingWithExpiredLeaseCanBeReclaimed() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);
        var later = NOW.plus(LEASE).plusSeconds(1);

        request.startProcessing(later, LEASE);

        assertThat(request.getAttempts()).isEqualTo(2);
        assertThat(request.isProcessingAttempt(1)).isFalse();
    }

    @Test
    void completeFromProcessing() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);

        request.complete(NOW.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getLockedUntil()).isNull();
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void scheduleRetryReturnsToPendingUntilNextAttempt() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);
        var next = NOW.plusSeconds(60);

        request.scheduleRetry(next, NOW);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.isClaimable(NOW.plusSeconds(59))).isFalse();
        assertThat(request.isClaimable(next)).isTrue();
    }

    @Test
    void failRecordsReason() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);

        request.fail("provider rejected request with 400", NOW);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("provider rejected request with 400");
    }

    @Test
    void outcomeTransitionsRequireProcessing() {
        var request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.scheduleRetry(NOW, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalStatesAcceptNoTransition() {
        var request = newRequest();
        request.startProcessing(NOW, LEASE);
        request.complete(NOW);

        assertThat(request.isClaimable(NOW.plusSeconds(3600))).isFalse();
        assertThatThrownBy(() -> request.startProcessing(NOW, LEASE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameSubmissionComparesClientAndContent() {
        var request = newRequest();

        assertThat(request.isSameSubmission("client-a", "CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.isSameSubmission("client-a", "CUST-1", "TV-100")).isFalse();
        assertThat(request.isSameSubmission("client-b", "CUST-1", "INTERNET-500")).isFalse();
    }
}
