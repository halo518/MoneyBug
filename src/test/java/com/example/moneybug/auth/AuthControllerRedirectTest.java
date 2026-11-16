package com.example.moneybug.auth;

import com.example.moneybug.auth.AuthService.Tokens;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthControllerRedirectTest {

    @Test
    void successEncodesTokens() {
        Tokens t = new Tokens("a.b+c", "r/x=y", "Bearer", 1800);
        assertEquals("https://f.x/#/auth?access=a.b%2Bc&refresh=r%2Fx%3Dy",
                AuthController.successRedirect("https://f.x", t));
    }

    @Test
    void errorRedirect() {
        assertEquals("https://f.x/#/auth?error=invalid_state",
                AuthController.errorRedirect("https://f.x", "invalid_state"));
    }

    @Test
    void stripsTrailingSlashes() {
        assertEquals("https://f.x", AuthController.stripTrailingSlash("https://f.x//"));
        assertEquals("https://f.x", AuthController.stripTrailingSlash("https://f.x"));
    }
}
