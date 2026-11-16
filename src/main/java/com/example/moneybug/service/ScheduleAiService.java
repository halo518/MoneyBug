package com.example.moneybug.service;

import com.example.moneybug.service.FixedShiftPlanner.Person;
import com.example.moneybug.service.FixedShiftPlanner.PartDef;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.util.Holidays;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** AI 근무표 생성: 프롬프트 만들기·응답 정리(레퍼런스 lib/ai.ts) + Ollama 호출(api/generate.ts). */
@Service
public class ScheduleAiService {

    private static final String[] DAYS = {"월", "화", "수", "목", "금", "토", "일"};
    private static final Pattern HHMM = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$|^24:00$");
    private static final Pattern STAFF_REF = Pattern.compile("#(\\d+)");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 생성에 쓰는 구성원. minor 는 프롬프트 표기용. */
    public record Member(Person person, boolean minor) {}

    /** 프롬프트·정리에 쓰는 입력. dates = 대상 날짜(오늘 이후). */
    public record GenInput(String month, StoreHours hours, List<PartDef> parts, List<Member> members,
                           List<LocalDate> dates, MonthSchedule.Priority priority) {}

    public record AiOutput(List<Planned> shifts, String comment) {}

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
        factory.setReadTimeout(Duration.ofSeconds(300));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    // ---------------------------------------------------------------- 프롬프트

    static String hhmm(int min) {
        return String.format("%02d:%02d", min / 60, min % 60);
    }

    /** 이름·연락처는 빼고 #번호로만 보낸다. 알바생은 고정 근무라 프롬프트에 넣지 않는다. */
    static String buildPrompt(GenInput in) {
        List<String> people = new ArrayList<>();
        for (Member m : in.members()) {
            Person p = m.person();
            if (p.partTimer()) continue;
            String base = "- #" + p.id() + " 직원" + (m.minor() ? "(연소자)" : "");
            if (hasWorkHours(p)) {
                List<String> d = new ArrayList<>();
                for (int i = 0; i < 7; i++) {
                    var w = i < p.workHours().size() ? p.workHours().get(i) : null;
                    d.add(DAYS[i] + " " + (w == null ? "쉼" : hhmm(w.startMin()) + "–" + hhmm(w.endMin())));
                }
                people.add(base + ", 고정 근무(요일별, 쉼인 요일은 근무 없음): " + String.join(" / ", d));
            } else {
                people.add(base + ", 영업시간 안이면 언제나 근무 가능");
            }
        }
        List<String> days = new ArrayList<>();
        for (LocalDate d : in.dates()) {
            int open = in.hours().open(d);
            int close = in.hours().close(d);
            boolean holiday = Holidays.isWeekendOrHoliday(d) && d.getDayOfWeek().getValue() <= 5;
            days.add(d + " (" + DAYS[d.getDayOfWeek().getValue() - 1] + (holiday ? ", 공휴일" : "") + ") "
                    + hhmm(open) + "–" + hhmm(close)
                    + (close > 1440 ? " (24시 이후는 익일, " + hhmm(close) + " = 익일 " + hhmm(close - 1440) + ")" : ""));
        }
        List<String> parts = in.parts().stream().sorted(Comparator.comparingInt(PartDef::startMin))
                .map(p -> "- " + p.name() + " " + hhmm(p.startMin()) + "–" + hhmm(p.endMin()))
                .toList();
        String priority = in.priority() == MonthSchedule.Priority.FAIRNESS
                ? "직원끼리 근무시간 차이를 줄인다."
                : "필요 인원만큼만 최소로 배치해 인건비를 줄인다.";
        return "당신은 한국 매장의 한 달 근무표를 짜는 도우미입니다. 아래 조건으로 " + in.month()
                + " 근무표를 JSON으로 만드세요.\n\n"
                + "## 영업일과 영업시간\n" + String.join("\n", days) + "\n\n"
                + "## 근무 파트 (알바생 고정 근무용, 참고)\n" + (parts.isEmpty() ? "- 없음" : String.join("\n", parts)) + "\n\n"
                + "## 구성원 (#번호 = staffId)\n" + String.join("\n", people) + "\n\n"
                + "## 규칙\n"
                + "1. 알바생은 고정 근무 파트대로 시스템이 넣는다. shifts에는 직원만 넣는다.\n"
                + "2. 고정 근무가 있으면 근무 요일마다 start/end를 그 요일 시각과 똑같이 넣는다. 없으면 출퇴근 시각을 15분 단위로 정해 알바생 고정 근무로 모자란 인원을 메운다.\n"
                + "3. 직원 근무는 그날 영업시간 안에서만 한다. 한 사람은 하루에 한 번만 근무한다.\n"
                + "4. 주(월~일) 근로시간은 40시간 이하, 연장 포함 52시간 이하.\n"
                + "5. " + priority + "\n"
                + "6. 휴게시간은 시스템이 자동으로 넣으니 출근~퇴근 시각만 주면 된다.\n\n"
                + "comment에는 배치 방식을 사장님께 2~3문장으로 요약한다. 구성원은 #번호로만 부른다.";
    }

    static boolean hasWorkHours(Person p) {
        return p.workHours() != null && p.workHours().stream().anyMatch(w -> w != null);
    }

    // ---------------------------------------------------------------- 응답 정리

    static int snap(int min) {
        return Math.round(min / 15f) * 15;
    }

    static int toMin(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
    }

    /** AI 응답을 믿지 않고 걸러 직원 근무로 만든다. 규칙에 안 맞는 줄은 버린다. */
    static List<Planned> toShifts(JsonNode out, GenInput in) {
        Map<Integer, Person> staff = new java.util.HashMap<>();
        for (Member m : in.members()) if (!m.person().partTimer()) staff.put(m.person().id(), m.person());
        Set<LocalDate> dates = new HashSet<>(in.dates());
        Set<String> seen = new HashSet<>();
        List<Planned> shifts = new ArrayList<>();
        JsonNode arr = out == null ? null : out.get("shifts");
        if (arr == null || !arr.isArray()) return shifts;
        for (JsonNode a : arr) {
            if (!a.path("staffId").isIntegralNumber()) continue;
            Person person = staff.get(a.get("staffId").asInt());
            LocalDate date = parseDate(a.path("date").asString(""));
            if (person == null || date == null || !dates.contains(date)
                    || seen.contains(person.id() + "|" + date)) continue;
            int start;
            int end;
            if (hasWorkHours(person)) {
                var w = person.workHours().size() > date.getDayOfWeek().getValue() - 1
                        ? person.workHours().get(date.getDayOfWeek().getValue() - 1) : null;
                if (w == null) continue;
                start = w.startMin();
                end = w.endMin();
            } else {
                String s = a.path("start").asString("");
                String e = a.path("end").asString("");
                if (!HHMM.matcher(s).matches() || !HHMM.matcher(e).matches()) continue;
                start = snap(toMin(s));
                end = snap(toMin(e));
            }
            if (start >= end || start < in.hours().open(date) || end > in.hours().close(date)) continue;
            seen.add(person.id() + "|" + date);
            shifts.add(new Planned(person.id(), date, start, end, null, FixedShiftPlanner.minBreakFor(end - start)));
        }
        return shifts;
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** AI 에는 번호만 보냈으니 코멘트의 #id 를 이름으로 되돌린다. */
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
                "properties", Map.of(
                        "shifts", Map.of("type", "array", "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "staffId", Map.of("type", "integer"),
                                        "date", Map.of("type", "string", "description", "YYYY-MM-DD"),
                                        "start", Map.of("type", "string", "description", "HH:MM, 15분 단위"),
                                        "end", Map.of("type", "string", "description", "HH:MM, 15분 단위")),
                                "required", List.of("staffId", "date", "start", "end"))),
                        "comment", Map.of("type", "string", "description", "사장님께 드리는 2~3문장 배치 요약")),
                "required", List.of("shifts", "comment"));
    }

    /** 프롬프트를 보내 AI 응답 JSON(message.content)을 돌려준다. 실패는 한국어 메시지의 ApiException. 키·프롬프트는 로그에 남기지 않는다. */
    public JsonNode generate(String prompt) {
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
