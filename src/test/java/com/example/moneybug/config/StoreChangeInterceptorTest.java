package com.example.moneybug.config;

import com.example.moneybug.service.StoreChangeHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 저장하지 않는 POST(미리보기·AI 질문)는 매장 변경 신호를 내지 않는다. */
@WebMvcTest(StoreChangeInterceptorTest.Probe.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(StoreChangeInterceptorTest.Probe.class)
@TestPropertySource(properties = "spring.profiles.include=")
class StoreChangeInterceptorTest {

    @RestController
    @RequestMapping("/api/v1/stores/{storeId}")
    static class Probe {
        @PostMapping({"/schedules/generate/preview", "/schedule-plans", "/ai/labor-questions",
                "/schedule-plans/{planId}/apply", "/shifts"})
        void ok() {}
    }

    @Autowired MockMvc mvc;
    @MockitoBean StoreChangeHub hub;

    @Test
    void previewsDoNotBumpButWritesDo() throws Exception {
        for (String p : new String[]{"/schedules/generate/preview", "/schedule-plans", "/ai/labor-questions"}) {
            mvc.perform(post("/api/v1/stores/5" + p));
        }
        verify(hub, never()).bump(any());
        for (String p : new String[]{"/schedule-plans/abc/apply", "/shifts"}) {
            clearInvocations(hub);
            mvc.perform(post("/api/v1/stores/5" + p));
            verify(hub).bump(5);
        }
    }
}
