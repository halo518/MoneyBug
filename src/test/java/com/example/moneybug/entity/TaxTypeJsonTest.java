package com.example.moneybug.entity;

import com.example.moneybug.dto.StoreCreateRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 설계서 §2: 공제(Deduction) enum 은 JSON 에서 biz33 / insurance / none 으로 주고받는다. */
class TaxTypeJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void deserializesDesignDocValues() {
        assertEquals(StoreWorker.TaxType.BUSINESS_TAX, mapper.readValue("\"biz33\"", StoreWorker.TaxType.class));
        assertEquals(StoreWorker.TaxType.FOUR_INSURANCE, mapper.readValue("\"insurance\"", StoreWorker.TaxType.class));
        assertEquals(StoreWorker.TaxType.NONE, mapper.readValue("\"none\"", StoreWorker.TaxType.class));
    }

    @Test
    void serializesToLowercaseNames() {
        assertEquals("\"biz33\"", mapper.writeValueAsString(StoreWorker.TaxType.BUSINESS_TAX));
        assertEquals("\"insurance\"", mapper.writeValueAsString(StoreWorker.TaxType.FOUR_INSURANCE));
        assertEquals("\"none\"", mapper.writeValueAsString(StoreWorker.TaxType.NONE));
    }

    @Test
    void stillAcceptsLegacyJavaNamesAndIgnoresCase() {
        assertEquals(StoreWorker.TaxType.BUSINESS_TAX, mapper.readValue("\"BUSINESS_TAX\"", StoreWorker.TaxType.class));
        assertEquals(StoreWorker.TaxType.FOUR_INSURANCE, mapper.readValue("\"FOUR_INSURANCE\"", StoreWorker.TaxType.class));
        assertEquals(StoreWorker.TaxType.BUSINESS_TAX, mapper.readValue("\"BIZ33\"", StoreWorker.TaxType.class));
    }

    @Test
    void rejectsUnknownValue() {
        assertThrows(RuntimeException.class, () -> mapper.readValue("\"bogus\"", StoreWorker.TaxType.class));
    }

    @Test
    void storeCreateRequestBodyFromDesignDocParses() {
        StoreCreateRequest r = mapper.readValue(
                "{\"name\":\"카페 커푸치노\",\"address\":\"도로명 주소\",\"isSmall\":true,\"defaultDeduction\":\"biz33\"}",
                StoreCreateRequest.class);

        assertEquals("카페 커푸치노", r.name());
        assertEquals("도로명 주소", r.address());
        assertTrue(r.isSmall());
        assertEquals(StoreWorker.TaxType.BUSINESS_TAX, r.defaultDeduction());
    }

    @Test
    void omittedDefaultDeductionIsNullSoServiceDefaultsApply() {
        StoreCreateRequest r = mapper.readValue("{\"name\":\"a\",\"address\":\"b\"}", StoreCreateRequest.class);
        assertNull(r.defaultDeduction());
        assertEquals(StoreWorker.TaxType.BUSINESS_TAX, r.toEntity(new User()).getDefaultDeduction());
    }
}
