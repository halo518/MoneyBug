package com.example.moneybug.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 근무표 브리핑은 공용 LlmClient 로 옮긴 뒤에도 같은 본문을 보낸다. */
class LlmClientTest {

    @Test
    void scheduleBriefingRequestIsUnchanged() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://llm.test/api/chat"))
                .andExpect(header("Authorization", "Bearer abc"))
                .andExpect(jsonPath("$.model").value("m"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.format.required[0]").value("comment"))
                .andExpect(jsonPath("$.options.num_ctx").value(8192))
                .andExpect(jsonPath("$.keep_alive").value("30m"))
                .andRespond(withSuccess("{\"message\":{\"content\":\"{\\\"comment\\\":\\\"좋아요\\\"}\"}}",
                        MediaType.APPLICATION_JSON));
        LlmClient llm = new LlmClient(builder.build(), "http://llm.test/", "m", " 'Bearer abc' ");
        assertTrue(llm.enabled());
        assertEquals("좋아요", new ScheduleAiService(llm).generate("p").path("comment").asString());
        server.verify();
        assertFalse(new LlmClient(builder.build(), "", "m", "").enabled());
    }
}
