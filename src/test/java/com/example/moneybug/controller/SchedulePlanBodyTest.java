package com.example.moneybug.controller;

import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.config.SecurityConfig;
import com.example.moneybug.dto.SchedulePlanDto;
import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.service.ShiftService;
import com.example.moneybug.service.StoreChangeHub;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 레거시 근무표 만들기·템플릿 본문: excludeStaffIds 의 null 은 400, copyLastWeek 생략은 그대로 통과. DB 없이. */
@WebMvcTest(ShiftController.class)
@Import({SecurityConfig.class, JwtProvider.class, StoreChangeHub.class})
@TestPropertySource(properties = {
        "jwt.secret=0123456789012345678901234567890123456789",
        "jwt.access-ttl-seconds=1800",
        "spring.profiles.include="})
class SchedulePlanBodyTest {

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @MockitoBean ShiftService service;
    @MockitoBean StoreRepository storeRepository;
    @MockitoBean StoreWorkerRepository storeWorkerRepository;

    @Test
    void nullExcludedStaffIdIs400() throws Exception {
        mvc.perform(post("/api/v1/stores/1/schedule-plans")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-12-01\",\"to\":\"2026-12-02\",\"needs\":[1],\"excludeStaffIds\":[null]}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).planSchedule(any(), any(), any());
    }

    @Test
    void copyLastWeekIsOptional() throws Exception {
        mvc.perform(post("/api/v1/stores/1/schedule-plans")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-12-01\",\"to\":\"2026-12-02\",\"needs\":[1]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<SchedulePlanDto.Request> plan = ArgumentCaptor.forClass(SchedulePlanDto.Request.class);
        verify(service).planSchedule(eq(7), eq(1), plan.capture());
        assertThat(plan.getValue().copyLastWeek()).isNull();

        mvc.perform(post("/api/v1/stores/1/schedule/template")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"needs\":[1,1,1]}"))
                .andExpect(status().isOk());
        verify(service).applyTemplate(eq(7), eq(1), any(ShiftCommandDto.TemplateRequest.class));
    }
}
