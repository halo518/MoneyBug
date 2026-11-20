package com.example.moneybug.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 사장님 자체 LLM 서버(Ollama 호환 /api/chat) 호출. 근무표 브리핑(ScheduleAiService)과 노무 도우미(AiLaborService)가 함께 쓴다.
 * 키·프롬프트·응답 본문은 로그에 남기지 않는다.
 */
@Component
public class LlmClient {

    /** 근무표 브리핑(짧은 문장)은 60초, 노무 답변(긴 문장·로컬 모델)은 180초까지 기다린다. */
    static final Duration SHORT_TIMEOUT = Duration.ofSeconds(60), LONG_TIMEOUT = Duration.ofSeconds(180);

    private final RestClient shortClient;
    private final RestClient longClient;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    @Autowired
    public LlmClient(@Value("${app.llm.base-url:https://ai.ruraeru.xyz}") String baseUrl,
                     @Value("${app.llm.model:qwen3.5:9b}") String model,
                     @Value("${app.llm.api-key:}") String apiKey) {
        this(client(SHORT_TIMEOUT), client(LONG_TIMEOUT), baseUrl, model, apiKey);
    }

    /** 테스트용: RestClient 를 직접 주입. */
    LlmClient(RestClient client, String baseUrl, String model, String apiKey) {
        this(client, client, baseUrl, model, apiKey);
    }

    private LlmClient(RestClient shortClient, RestClient longClient, String baseUrl, String model, String apiKey) {
        this.shortClient = shortClient;
        this.longClient = longClient;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.model = model;
        // 대시보드에 붙여넣을 때 섞이는 공백·따옴표·"Bearer " 제거
        this.apiKey = apiKey == null ? ""
                : apiKey.trim().replaceAll("^[\"']|[\"']$", "").replaceFirst("(?i)^Bearer\\s+", "");
    }

    private static RestClient client(Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(readTimeout);
        // Boot 4 는 RestClient.Builder 빈이 없다(spring-boot-restclient 모듈 필요). 직접 만든다.
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 서버 주소가 비어 있으면 꺼짐. */
    public boolean enabled() {
        return !baseUrl.isBlank();
    }

    /**
     * messages 를 보내고 message.content 원문을 돌려준다(JSON 해석은 호출자). format = 응답 JSON 스키마.
     * 연결·HTTP 오류는 502 AI_FAILED.
     */
    public String chat(List<Map<String, String>> messages, Map<String, Object> format, int numCtx, boolean longWait) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "format", format,
                "think", false,
                "stream", false,
                "options", Map.of("num_ctx", numCtx),
                "keep_alive", "30m");
        JsonNode res;
        try {
            RestClient.RequestBodySpec req = (longWait ? longClient : shortClient).post().uri(baseUrl + "/api/chat")
                    .contentType(MediaType.APPLICATION_JSON);
            if (!apiKey.isBlank()) req = req.header("Authorization", "Bearer " + apiKey);
            res = req.body(body).retrieve().body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw failed("AI 서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403) throw failed("AI 서버가 인증을 거절했어요(" + code + "). 서버 설정을 확인해 주세요.");
            throw failed("AI 서버 오류가 났어요(" + code + ").");
        } catch (Exception e) {
            throw failed("AI 호출에 실패했어요.");
        }
        return res == null ? "" : res.path("message").path("content").asString("");
    }

    static ApiException failed(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", message);
    }
}
