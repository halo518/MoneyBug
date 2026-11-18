package com.example.moneybug.service;

import com.example.moneybug.dto.AiDto;
import com.example.moneybug.entity.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** AI 노무 도우미: Anthropic Messages API 직접 호출. 사용자당 시간당 20회 제한(인메모리). */
@Service
public class AiLaborService {

    static final int LIMIT_PER_HOUR = 20;
    static final String URL = "https://api.anthropic.com/v1/messages";
    static final int MAX_QUESTION = 1000;
    private static final java.util.regex.Pattern GRAPHEME = java.util.regex.Pattern.compile("\\X");

    /** 글자 수: FE 입력칸(maxLength)처럼 그래핌 단위로 센다(이모지 1개 = 1자). */
    static long length(String s) {
        return GRAPHEME.matcher(s).results().count();
    }

    private final StoreAccess access;
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final Map<Integer, Deque<Long>> calls = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AiLaborService(StoreAccess access,
                          @Value("${app.ai.api-key:}") String apiKey,
                          @Value("${app.ai.model:claude-sonnet-5-5}") String model) {
        this.access = access;
        this.apiKey = apiKey;
        this.model = model;
        var factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        // Boot 4 는 RestClient.Builder 빈이 없다(spring-boot-restclient 모듈 필요). 직접 만든다.
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** 테스트용: RestClient 를 직접 주입. */
    AiLaborService(StoreAccess access, RestClient client, String apiKey, String model) {
        this.access = access;
        this.client = client;
        this.apiKey = apiKey;
        this.model = model;
    }

    public AiDto.Response ask(Integer userId, Integer storeId, String question) {
        Store store = access.requireOwner(userId, storeId);
        if (question != null && length(question) > MAX_QUESTION) {
            throw ApiException.badRequest("QUESTION_TOO_LONG", "질문은 " + MAX_QUESTION + "자까지 쓸 수 있어요");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "AI 도우미가 아직 설정되지 않았어요");
        }
        checkRate(userId);
        String answer;
        try {
            JsonNode res = client.post().uri(URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(store, question))
                    .retrieve()
                    .body(JsonNode.class);
            answer = parseAnswer(res);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 키·본문이 로그에 남지 않게 예외 종류만 기록한다.
            throw failed();
        }
        if (answer.isBlank()) throw failed();
        return new AiDto.Response(answer, LocalDateTime.now());
    }

    Map<String, Object> buildBody(Store store, String question) {
        return Map.of(
                "model", model,
                "max_tokens", 1500,
                "system", system(store),
                "messages", List.of(Map.of("role", "user", "content", question)));
    }

    static String system(Store store) {
        String deduction = store.getDefaultDeduction() == null ? "미정" : store.getDefaultDeduction().name();
        return "너는 한국 근로기준법 기준으로 소상공인 사장님을 돕는 노무 도우미야. "
                + "매장 정보: 5인 미만 사업장 여부=" + (store.isUnderFive() ? "예" : "아니오")
                + ", 기본 공제=" + deduction + ". 이 정보를 맥락으로 답해. "
                + "짧고 실용적으로, 존댓말(~해요)로 답해. 근거 법 조항을 알면 함께 적어. "
                + "확실하지 않으면 추측하지 말고 노무사나 고용노동부(1350) 확인을 권해. "
                + "마지막에 법률 자문이 아니라 참고용이라고 짧게 알려.";
    }

    static String parseAnswer(JsonNode res) {
        StringBuilder sb = new StringBuilder();
        if (res != null && res.path("content").isArray()) {
            for (JsonNode c : res.path("content")) {
                if ("text".equals(c.path("type").asString())) sb.append(c.path("text").asString());
            }
        }
        return sb.toString().trim();
    }

    private void checkRate(Integer userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = calls.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
            if (q.size() >= LIMIT_PER_HOUR) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMIT",
                        "AI 질문은 1시간에 " + LIMIT_PER_HOUR + "번까지 할 수 있어요. 잠시 뒤에 다시 물어보세요");
            }
            q.addLast(now);
        }
    }

    private static ApiException failed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "AI 답변을 가져오지 못했어요. 잠시 뒤에 다시 시도해 주세요");
    }
}
