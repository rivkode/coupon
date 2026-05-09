package com.promotion.serverb.infrastructure.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeysTest {

    @Test
    void pendingHashIncludesUserAndType() {
        assertEquals("issue:pending:42:7", RedisKeys.pendingHash(42, 7));
    }

    @Test
    void pendingZsetMember() {
        assertEquals("42:7", RedisKeys.pendingZsetMember(42, 7));
    }
}
