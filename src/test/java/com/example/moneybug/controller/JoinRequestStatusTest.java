package com.example.moneybug.controller;

import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.config.SecurityConfig;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.service.StoreChangeHub;
import com.example.moneybug.service.StoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FE requestJoinRemote 는 2xx 본문의 result 로 분기한다: requested 만 201, 나머지는 200 + 같은 본문. */
@WebMvcTest(StoreController.class)
@Import({SecurityConfig.class, JwtProvider.class})
@TestPropertySource(properties = {
        "jwt.secret=0123456789012345678901234567890123456789",
        "jwt.access-ttl-seconds=1800",
        "spring.profiles.include="})
class JoinRequestStatusTest {

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @MockitoBean StoreService service;
    @MockitoBean StoreChangeHub changeHub;
    @MockitoBean StoreRepository storeRepository;
    @MockitoBean StoreWorkerRepository storeWorkerRepository;

    private void expect(StoreDetailDto.JoinResult result, StoreDetailDto.JoinStoreRef ref, int status, String json)
            throws Exception {
        when(service.requestJoin(anyInt(), anyString())).thenReturn(new StoreDetailDto.JoinResponse(result, ref));
        mvc.perform(post("/api/v1/stores/join-requests")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ABCD-EFGH\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.result").value(json));
    }

    @Test
    void onlyRequestedIs201AndSignals() throws Exception {
        var ref = new StoreDetailDto.JoinStoreRef(4, "카페");
        expect(StoreDetailDto.JoinResult.NOT_FOUND, null, 200, "notFound");
        expect(StoreDetailDto.JoinResult.ALREADY_PENDING, ref, 200, "alreadyPending");
        expect(StoreDetailDto.JoinResult.ALREADY_MEMBER, ref, 200, "alreadyMember");
        verifyNoInteractions(changeHub);
        expect(StoreDetailDto.JoinResult.REQUESTED, ref, 201, "requested");
        verify(changeHub).bump(4);
    }
}
