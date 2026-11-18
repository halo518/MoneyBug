package com.example.moneybug.controller;

import com.example.moneybug.config.RequestStringModule;
import com.example.moneybug.service.StoreChangeHub;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 프레임워크·DB 단계 오류도 {code, message, details} 로, 요청 문자열 제어문자 제거, 정수 칸의 소수 거절. */
@WebMvcTest(ApiExceptionHandlerTest.Probe.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandlerTest.Probe.class, RequestStringModule.class, StoreChangeHub.class})
@TestPropertySource(properties = "spring.profiles.include=")
class ApiExceptionHandlerTest {

    record Body(@NotNull @JsonProperty("wage") Integer hourlyWage,
                @Size(max = 3) String name,
                @Size(max = 2, message = "둘까지만") List<String> tags) {}

    @RestController
    static class Probe {
        @PostMapping("/t/body")
        Body body(@Valid @RequestBody Body b) {
            return b;
        }

        @GetMapping("/t/{id}")
        String path(@PathVariable Integer id, @RequestParam int need) {
            return "ok";
        }

        @GetMapping("/t/db/{kind}")
        String db(@PathVariable String kind) {
            throw switch (kind) {
                case "dup" -> new DataIntegrityViolationException("Duplicate entry 'secret'",
                        new SQLException("Duplicate entry 'secret'", "23000", 1062));
                case "long" -> new DataIntegrityViolationException("Data too long",
                        new SQLException("Data too long for column 'part'", "22001", 1406));
                case "deadlock" -> new CannotAcquireLockException("Deadlock found");
                default -> new ObjectOptimisticLockingFailureException(Object.class, 1);
            };
        }
    }

    @Autowired MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions postBody(String json) throws Exception {
        return mvc.perform(post("/t/body").contentType("application/json").content(json));
    }

    @Test
    void brokenJsonAndWrongTypes() throws Exception {
        postBody("{\"wage\":").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        postBody("{\"wage\":\"abc\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.wage").value("형식이 올바르지 않아요"));
    }

    @Test
    void floatForIntIsRejectedNotTruncated() throws Exception {
        postBody("{\"wage\":10320.5}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
        postBody("{\"wage\":10320}").andExpect(status().isOk()).andExpect(jsonPath("$.wage").value(10320));
    }

    @Test
    void validationDetailsUseFeKeysAndKorean() throws Exception {
        postBody("{\"name\":\"abcd\",\"tags\":[\"a\",\"b\",\"c\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.wage").value("값을 입력해 주세요"))
                .andExpect(jsonPath("$.details.name").value("3자 이하로 입력해 주세요"))
                .andExpect(jsonPath("$.details.tags").value("둘까지만"));
    }

    @Test
    void controlCharsAreStrippedButNewlinesAndTabsKept() throws Exception {
        postBody("{\"wage\":1,\"name\":\"a\\u0000b\",\"tags\":[\"x\\u0007\\ny\\tz\\r\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ab"))
                .andExpect(jsonPath("$.tags[0]").value("x\ny\tz"));
    }

    @Test
    void paramErrors() throws Exception {
        mvc.perform(get("/t/abc?need=1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM")).andExpect(jsonPath("$.details.id").exists());
        mvc.perform(get("/t/1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAM")).andExpect(jsonPath("$.details.need").exists());
        mvc.perform(post("/t/body").contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(delete("/t/body")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void dbConflictsAre409AndBadValuesAre400() throws Exception {
        for (String k : List.of("dup", "deadlock", "optimistic")) {
            mvc.perform(get("/t/db/" + k)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("다시 시도해 주세요")));
        }
        mvc.perform(get("/t/db/long")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("part"))));
    }
}
