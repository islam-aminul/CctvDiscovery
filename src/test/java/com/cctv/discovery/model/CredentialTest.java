package com.cctv.discovery.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CredentialTest {

    @Test
    void listNeverShowsThePassword() {
        String shown = new Credential("admin", "Secret-Pass9").toDisplayString();
        assertFalse(shown.contains("Secret-Pass9"), shown);
        assertEquals("admin : S***9", shown);
    }

    @Test
    void shortAndBlankPasswords() {
        assertEquals("admin : **", new Credential("admin", "ab").toDisplayString());
        assertEquals("admin : (no password)", new Credential("admin", "").toDisplayString());
    }
}
