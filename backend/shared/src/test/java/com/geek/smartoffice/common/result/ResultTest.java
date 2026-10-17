package com.geek.smartoffice.common.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultTest {

    @Test
    void successKeepsRecoveredFrontendEnvelope() {
        Result<String> result = Result.success("token");
        assertEquals(200, result.code());
        assertEquals("success", result.message());
        assertEquals("token", result.data());
    }

    @Test
    void errorHasNoData() {
        Result<Void> result = Result.error(409, "预约时间冲突");
        assertEquals(409, result.code());
        assertNull(result.data());
    }
}
