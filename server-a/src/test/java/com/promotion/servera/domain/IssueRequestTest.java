package com.promotion.servera.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IssueRequestTest {

    @Test
    void createsWithRandomRequestId() {
        IssueRequest req = IssueRequest.of(1L, 2L, 3L, IssueRequestStatus.ACCEPTED, Instant.now());

        assertNotNull(req.getRequestId());
        assertEquals(IssueRequestStatus.ACCEPTED, req.getStatus());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> IssueRequest.of(null, 2L, 3L, IssueRequestStatus.ACCEPTED, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> IssueRequest.of(1L, null, 3L, IssueRequestStatus.ACCEPTED, Instant.now()));
    }
}
