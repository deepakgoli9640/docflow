package com.hpg.gateway.denylist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantSnapshotListenerTest {

    final MerchantDenyList denyList = mock(MerchantDenyList.class);
    final MerchantSnapshotListener listener = new MerchantSnapshotListener(denyList, new ObjectMapper());

    @Test
    void appliesTheSnapshotStatus() {
        UUID id = UUID.randomUUID();
        when(denyList.apply(id, "SUSPENDED")).thenReturn(Mono.just(true));
        listener.onSnapshot("{\"eventType\":\"MerchantSnapshot\",\"payload\":{\"merchantId\":\"" + id
                + "\",\"status\":\"SUSPENDED\",\"snapshotVersion\":3}}");
        verify(denyList).apply(id, "SUSPENDED");
    }

    @Test
    void ignoresOtherEventsAndMalformedRecords() {
        listener.onSnapshot("{\"eventType\":\"SomethingElse\",\"payload\":{}}");
        listener.onSnapshot("not json");
        listener.onSnapshot("{\"eventType\":\"MerchantSnapshot\",\"payload\":{}}");
        verify(denyList, never()).apply(any(), anyString());
    }

    @Test
    void onlySuspendedAndDeactivatedAreDenied() {
        org.assertj.core.api.Assertions.assertThat(MerchantDenyList.deniedStatus("SUSPENDED")).isTrue();
        org.assertj.core.api.Assertions.assertThat(MerchantDenyList.deniedStatus("DEACTIVATED")).isTrue();
        org.assertj.core.api.Assertions.assertThat(MerchantDenyList.deniedStatus("ACTIVE")).isFalse();
        org.assertj.core.api.Assertions.assertThat(MerchantDenyList.deniedStatus("PENDING_ACTIVATION")).isFalse();
    }
}
