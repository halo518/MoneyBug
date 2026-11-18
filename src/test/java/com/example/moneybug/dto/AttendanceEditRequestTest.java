package com.example.moneybug.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttendanceEditRequestTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private AttendanceViewDto.EditRequest read(String clockIn) {
        return json.readValue("{\"clockIn\":" + clockIn + ",\"reason\":\"x\"}", AttendanceViewDto.EditRequest.class);
    }

    @Test
    void offsetTimesAreConvertedToKst() {
        LocalDateTime kst = LocalDateTime.of(2026, 11, 17, 22, 45);
        assertEquals(kst, read("\"2026-11-17T22:45:00.000\"").clockIn()); // FE 형식(오프셋 없음) = KST
        assertEquals(kst, read("\"2026-11-17T13:45:00Z\"").clockIn());
        assertEquals(kst, read("\"2026-11-17T22:45:00+09:00\"").clockIn());
        assertNull(read("null").clockIn());
        assertThrows(RuntimeException.class, () -> read("\"2026-11-17 22:45\""));
    }

    @Test
    void reasonLengthLimited() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        assertTrue(v.validate(new AttendanceViewDto.EditRequest(null, null, "가".repeat(500))).isEmpty());
        assertEquals(1, v.validate(new AttendanceViewDto.EditRequest(null, null, "가".repeat(501))).size());
    }
}
