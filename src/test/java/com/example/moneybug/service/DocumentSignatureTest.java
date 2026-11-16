package com.example.moneybug.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DocumentSignatureTest {

    @Test
    void ownSignatureOk() {
        assertDoesNotThrow(() -> DocumentService.requireOwnSignature(7, "signatures/7/a.png"));
    }

    @Test
    void rejectsOthersTraversalAndFake() {
        assertThrows(ApiException.class, () -> DocumentService.requireOwnSignature(7, "signatures/8/a.png"));
        assertThrows(ApiException.class, () -> DocumentService.requireOwnSignature(7, "signatures/7/../8/a.png"));
        assertThrows(ApiException.class, () -> DocumentService.requireOwnSignature(7, "app-signature"));
        assertThrows(ApiException.class, () -> DocumentService.requireOwnSignature(7, null));
    }
}
