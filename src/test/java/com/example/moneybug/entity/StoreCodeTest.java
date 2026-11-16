package com.example.moneybug.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreCodeTest {

    @Test
    void codeHasEightCharsForFeInput() {
        for (int i = 0; i < 100; i++) {
            String code = Store.newCode();
            assertTrue(code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}"), code);
        }
    }
}
