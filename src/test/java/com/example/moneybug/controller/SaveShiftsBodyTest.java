package com.example.moneybug.controller;

import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.config.SecurityConfig;
import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.service.MonthScheduleService;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FE 가 보내는 PUT /schedules/{month}/shifts 본문(id 숫자, kind "work")이 그대로 서비스에 닿는지. DB 없이. */
@WebMvcTest(ScheduleController.class)
@Import({SecurityConfig.class, JwtProvider.class, StoreChangeHub.class})
@TestPropertySource(properties = {
        "jwt.secret=0123456789012345678901234567890123456789",
        "jwt.access-ttl-seconds=1800",
        "spring.profiles.include="})
class SaveShiftsBodyTest {

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @MockitoBean MonthScheduleService service;
    @MockitoBean StoreRepository storeRepository;
    @MockitoBean StoreWorkerRepository storeWorkerRepository;

    @Test
    void numericIdAndCamelKindReachService() throws Exception {
        String body = """
                {"shifts":[
                  {"id":123,"staffId":45,"date":"2026-12-03","startMin":540,"endMin":1080,
                   "part":"오픈","breakMin":30,"kind":"work","timeTbd":false},
                  {"staffId":45,"date":"2026-12-04","startMin":540,"endMin":1080,
                   "part":"오픈","breakMin":30,"kind":"leave","timeTbd":false}]}""";
        mvc.perform(put("/api/v1/stores/1/schedules/2026-12/shifts")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<MonthScheduleCommandDto.SaveShiftsRequest> req =
                ArgumentCaptor.forClass(MonthScheduleCommandDto.SaveShiftsRequest.class);
        verify(service).saveShifts(eq(7), eq(1), eq("2026-12"), req.capture());
        var a = req.getValue().shifts().get(0);
        var b = req.getValue().shifts().get(1);
        assertThat(a.id()).isEqualTo("123");
        assertThat(a.staffId()).isEqualTo(45);
        assertThat(a.date()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(a.kind()).isEqualTo(Schedule.Kind.WORK);
        assertThat(b.id()).isNull();
        assertThat(b.kind()).isEqualTo(Schedule.Kind.LEAVE);
    }
}
