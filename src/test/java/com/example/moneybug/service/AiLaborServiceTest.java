package com.example.moneybug.service;

import com.example.moneybug.entity.Store;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiLaborServiceTest {

    private final StoreAccess access = mock(StoreAccess.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    AiLaborServiceTest() {
        Store store = new Store();
        store.setUnderFive(true);
        when(access.requireOwner(1, 1)).thenReturn(store);
    }

    private AiLaborService svc(String key) {
        return new AiLaborService(access, builder.build(), key, "test-model");
    }

    @Test
    void noKeyIsDisabled() {
        ApiException e = assertThrows(ApiException.class, () -> svc("").ask(1, 1, "q"));
        assertEquals("AI_DISABLED", e.getCode());
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void buildsRequestAndParsesTextBlocks() {
        server.expect(requestTo(AiLaborService.URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "k"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.max_tokens").value(1500))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("주휴수당?"))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.containsString("5인 미만 사업장 여부=예")))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"네, \"},"
                        + "{\"type\":\"tool_use\"},{\"type\":\"text\",\"text\":\"줘야 해요.\"}]}",
                        MediaType.APPLICATION_JSON));
        assertEquals("네, 줘야 해요.", svc("k").ask(1, 1, "주휴수당?").getAnswer());
        server.verify();
    }

    @Test
    void upstreamFailureIs502() {
        server.expect(requestTo(AiLaborService.URL)).andRespond(withServerError());
        ApiException e = assertThrows(ApiException.class, () -> svc("k").ask(1, 1, "q"));
        assertEquals("AI_FAILED", e.getCode());
    }

    @Test
    void rateLimitAfter20() {
        for (int i = 0; i < AiLaborService.LIMIT_PER_HOUR; i++) {
            server.expect(requestTo(AiLaborService.URL))
                    .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}",
                            MediaType.APPLICATION_JSON));
        }
        AiLaborService s = svc("k");
        for (int i = 0; i < AiLaborService.LIMIT_PER_HOUR; i++) s.ask(1, 1, "q");
        ApiException e = assertThrows(ApiException.class, () -> s.ask(1, 1, "q"));
        assertEquals("AI_RATE_LIMIT", e.getCode());
        assertEquals(429, e.getStatus().value());
    }
}
