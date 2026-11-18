package com.example.moneybug.controller;

import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.config.SecurityConfig;
import com.example.moneybug.service.MeService;
import com.example.moneybug.service.StateService;
import com.example.moneybug.service.StoreChangeHub;
import com.example.moneybug.service.WatchedStores;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /me/changes 롱 폴링이 보안 필터(비동기 디스패치 포함)를 지나 동작하는지. DB 없이. */
@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, JwtProvider.class, StoreChangeHub.class})
@TestPropertySource(properties = {
        "jwt.secret=0123456789012345678901234567890123456789",
        "jwt.access-ttl-seconds=1800",
        "spring.profiles.include="})
class MeChangesLongPollTest {

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired StoreChangeHub hub;
    @MockitoBean MeService meService;
    @MockitoBean StateService stateService;
    @MockitoBean WatchedStores watchedStores;

    private String auth() {
        when(watchedStores.of(7)).thenReturn(Set.of(1));
        return "Bearer " + jwt.createAccessToken(7L);
    }

    @Test
    void waitsThenReportsChangeOfMyStore() throws Exception {
        String auth = auth();
        MvcResult base = mvc.perform(get("/api/v1/me/changes").header("Authorization", auth))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(base)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
        long since = ((Number) ((java.util.Map<?, ?>) base.getAsyncResult()).get("seq")).longValue();

        MvcResult wait = mvc.perform(get("/api/v1/me/changes?since=" + since).header("Authorization", auth))
                .andExpect(request().asyncStarted()).andReturn();
        hub.bump(1);
        mvc.perform(asyncDispatch(wait)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void badSinceIsApiError() throws Exception {
        mvc.perform(get("/api/v1/me/changes?since=abc").header("Authorization", auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/v1/me/changes")).andExpect(status().isUnauthorized());
    }
}
