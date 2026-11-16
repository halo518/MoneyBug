package com.example.moneybug.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.moneybug.entity.StoreWorker;
import org.junit.jupiter.api.Test;

class EnumQueryConverterTest {

    private final EnumQueryConverter c = new EnumQueryConverter();

    @Test
    void acceptsLowerAndUpperCase() {
        assertEquals(StoreWorker.Status.PENDING, c.convert("pending"));
        assertEquals(StoreWorker.Status.ACTIVE, c.convert("ACTIVE"));
        assertNull(c.convert(" "));
    }
}
