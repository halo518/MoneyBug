package com.example.moneybug.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffCommandValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void wageCapOnApproveAndPatch() {
        var approve = validator.validate(new StaffCommandDto.ApproveRequest(1_000_001, null));
        assertEquals(1, approve.size());
        assertEquals("시급은 1,000,000원 이하로 입력해 주세요", approve.iterator().next().getMessage());

        var patch = validator.validate(new StaffCommandDto.UpdateRequest(Integer.MAX_VALUE,
                null, null, null, null, null, null, null, null));
        assertEquals(1, patch.size());

        assertTrue(validator.validate(new StaffCommandDto.ApproveRequest(1_000_000, null)).isEmpty());
    }
}
