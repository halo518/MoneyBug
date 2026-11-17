package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** AI 근무표 브리핑: 계산된 요약을 사장님께 설명하는 문장만 Ollama 호환 서버에서 받는다. 근무 배치는 AI 가 하지 않는다. */
@Service
public class ScheduleAiService {

    private static final Pattern STAFF_REF = Pattern.compile("#(\\d+)");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient client;
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    @Autowired
    public ScheduleAiService(@Value("${app.llm.base-url:https://ai.ruraeru.xyz}") String baseUrl,
                             @Value("${app.llm.model:qwen3.5:9b}") String model,
                             @Value("${app.llm.api-key:}") String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        // 대시보드에 붙여넣을 때 섞이는 공백·따옴표·"Bearer " 제거
        this.apiKey = apiKey.trim().replaceAll("^[\"']|[\"']$", "").replaceFirst("(?i)^Bearer\\s+", "");
        var factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    // ---------------------------------------------------------------- 프롬프트

    /** 이름은 빼고 #번호로만 보낸다. idLines = #번호 기준 요약 문장. */
    static String briefPrompt(String month, List<String> idLines, MonthSchedule.Priority priority,
                              MonthSchedule.HolidayPolicy policy) {
        String pri = priority == MonthSchedule.Priority.FAIRNESS ? "직원끼리 근무시간 공평하게" : "인건비 절감";
        String hol = policy == MonthSchedule.HolidayPolicy.ALLOW ? "주휴수당 허용" : "주휴수당 최소화";
        return "당신은 한국 매장 사장님을 돕는 도우미입니다. 아래는 " + month + " 근무표를 계산한 결과 요약입니다"
                + "(우선순위: " + pri + ", " + hol + ").\n\n"
                + "## 계산된 요약\n" + String.join("\n", idLines) + "\n\n"
                + "이 근무표를 사장님께 2~3문장으로 쉽게 설명하세요. 숫자는 바꾸지 말고, 구성원은 #번호로만 부르세요. "
                + "요약에 없는 내용은 지어내지 마세요.";
    }

    /** AI 에는 번호만 보냈으니 문장의 #id 를 이름으로 되돌린다. */
    static String nameBack(String comment, Map<Integer, String> names) {
        if (comment == null) return "";
        return STAFF_REF.matcher(comment).replaceAll(m -> {
            String n = names.get(Integer.parseInt(m.group(1)));
            return java.util.regex.Matcher.quoteReplacement(n != null ? n : m.group());
        });
    }

    // ---------------------------------------------------------------- 호출

    static Map<String, Object> responseSchema() {
        return Map.of("type", "object",
                "properties", Map.of("comment", Map.of("type", "string", "description", "사장님께 드리는 2~3문장 설명")),
                "required", List.of("comment"));
    }

    /** 프롬프트를 보내 AI 응답 JSON(message.content)을 돌려준다. 실패하면 한 번 더 시도하고, 그래도 안 되면 ApiException. 키·프롬프트는 로그에 남기지 않는다. */
    public JsonNode generate(String prompt) {
        try {
            return call(prompt);
        } catch (ApiException first) {
            return call(prompt);
        }
    }

    private JsonNode call(String prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "format", responseSchema(),
                "think", false,
                "stream", false,
                "options", Map.of("num_ctx", 8192),
                "keep_alive", "30m");
        JsonNode res;
        try {
            RestClient.RequestBodySpec req = client.post().uri(baseUrl + "/api/chat")
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
        String content = res == null ? "" : res.path("message").path("content").asString("");
        try {
            return MAPPER.readTree(content);
        } catch (Exception e) {
            throw failed("AI 응답이 잘렸거나 JSON이 아니에요. 다시 시도해 주세요.");
        }
    }

    private static ApiException failed(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", message);
    }
}
