package com.example.moneybug.service;

import com.example.moneybug.dto.AiDto;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * AI 노무 도우미: 사장님 자체 LLM 서버(LlmClient)로 노무 질문에 답하고(사장님·재직 직원), 노무 점검(사장님)을 한다.
 * 이름은 LLM 에 보내지 않는다(별칭 직원1… 로 바꾸고 답에서 되돌린다). 사용자당 시간당 20회(질문·점검 합산, 인메모리).
 */
@Service
@RequiredArgsConstructor
public class AiLaborService {

    static final int LIMIT_PER_HOUR = 20;
    static final int MAX_QUESTION = 1000;
    static final int MAX_CITATIONS = 8, MAX_CITATION_LEN = 60;
    /** 맥락(직원 50명)까지 들어가도록 근무표 브리핑(8192)보다 넉넉하게. */
    static final int NUM_CTX = 16384;
    static final Pattern ALIAS = Pattern.compile("직원(\\d+)");
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 글자 수: FE 입력칸(maxLength)처럼 그래핌 단위로 센다(이모지 1개 = 1자). */
    static long length(String s) {
        return GRAPHEME.matcher(s).results().count();
    }

    private final StoreAccess access;
    private final StoreWorkerRepository storeWorkerRepository;
    private final ContractRepository contractRepository;
    private final ScheduleRepository scheduleRepository;
    private final StaffWageService staffWageService;
    private final LlmClient llm;
    private final Map<Integer, Deque<Long>> calls = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 질문

    public AiDto.Response ask(Integer userId, Integer storeId, String question) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        boolean owner = access.isOwner(store, userId);
        StoreWorker me = owner ? null : access.activeStaff(userId, storeId)
                .orElseThrow(() -> ApiException.forbidden("이 매장의 사장님 또는 재직 직원만 사용할 수 있어요"));
        if (question != null && length(question) > MAX_QUESTION) {
            throw ApiException.badRequest("QUESTION_TOO_LONG", "질문은 " + MAX_QUESTION + "자까지 쓸 수 있어요");
        }
        requireEnabled();
        checkRate(userId);

        LocalDate today = TimeUtil.today();
        List<StoreWorker> active = active(storeId);
        LaborContext.StoreFacts facts = facts(store, active.size());
        LocalDate first = today.withDayOfMonth(1), last = today.with(TemporalAdjusters.lastDayOfMonth());
        Map<String, String> mask = new HashMap<>();
        Map<Integer, String> restore = new HashMap<>();
        String system;
        if (owner) {
            for (int i = 0; i < active.size(); i++) {
                putNames(mask, active.get(i).getWorker(), "직원" + (i + 1));
                restore.put(i + 1, active.get(i).getWorker().getName());
            }
            Map<Integer, Integer> monthMin = new HashMap<>();
            for (Schedule s : scheduleRepository.findByStoreBetween(storeId, first, last)) {
                if (s.isWork()) monthMin.merge(s.getStoreWorker().getId(), s.scheduledMinutes(), Integer::sum);
            }
            List<LaborContext.Staff> lines = new ArrayList<>();
            for (int i = 0; i < Math.min(active.size(), LaborContext.MAX_LINES); i++) {
                StoreWorker sw = active.get(i);
                lines.add(staff(sw, "직원" + (i + 1), avgWeekly(monthMin.getOrDefault(sw.getId(), 0), last), today));
            }
            String context = LaborContext.ownerContext(facts, lines, active.size() - lines.size(), today);
            system = LaborContext.ownerSystem(context);
        } else {
            // 직원 질문: 동료 이름은 "동료", 본인 이름은 "본인" 으로 가린다. 별칭표·동료 정보는 보내지 않는다.
            for (StoreWorker sw : active) putNames(mask, sw.getWorker(), sw.getId().equals(me.getId()) ? "본인" : "동료");
            int min = 0;
            for (Schedule s : scheduleRepository.findByWorkerBetween(me.getId(), first, last)) {
                if (s.isWork()) min += s.scheduledMinutes();
            }
            system = LaborContext.staffSystem(LaborContext.staffContext(facts,
                    staff(me, "질문자 본인", avgWeekly(min, last), today), today));
        }
        putNames(mask, store.getEmployer(), "사장님");

        String content;
        try {
            content = llm.chat(List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", LaborContext.mask(question, mask))), answerSchema(), NUM_CTX, true);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 키·본문이 로그에 남지 않게 예외 종류만 기록한다.
            throw failed();
        }
        Parsed p = parseAnswer(content);
        String answer = ScheduleAiService.nameBack(p.answer(), ALIAS, restore).trim();
        if (answer.isBlank()) throw failed();
        List<String> citations = cleanCitations(p.citations().stream()
                .map(c -> ScheduleAiService.nameBack(c, ALIAS, restore)).toList());
        return new AiDto.Response(answer, citations, LocalDateTime.now());
    }

    record Parsed(String answer, List<String> citations) {}

    /** {answer, citations} JSON 이 아니면 받은 글 전체를 답으로. */
    static Parsed parseAnswer(String content) {
        String text = content == null ? "" : content.trim();
        try {
            JsonNode n = MAPPER.readTree(text);
            if (n != null && n.isObject() && n.path("answer").isString()) {
                List<String> cites = new ArrayList<>();
                if (n.path("citations").isArray()) {
                    for (JsonNode c : n.path("citations")) if (c.isString()) cites.add(c.asString());
                }
                return new Parsed(n.path("answer").asString(), cites);
            }
        } catch (Exception ignored) {
            // JSON 이 아니면 글 그대로
        }
        return new Parsed(text, List.of());
    }

    /** 공백 정리, 빈 것·중복·60자 넘는 것 버림, 최대 8개. */
    static List<String> cleanCitations(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : raw) {
            String t = c == null ? "" : c.trim().replaceAll("\\s+", " ");
            if (t.isEmpty() || length(t) > MAX_CITATION_LEN) continue;
            out.add(t);
            if (out.size() == MAX_CITATIONS) break;
        }
        return List.copyOf(out);
    }

    static Map<String, Object> answerSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "answer", Map.of("type", "string", "description", "질문에 대한 답변"),
                        "citations", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "근거 법 조항. 예: 근로기준법 제55조")),
                "required", List.of("answer", "citations"));
    }

    // ---------------------------------------------------------------- 점검

    public AiDto.AuditResponse audit(Integer userId, Integer storeId) {
        Store store = access.requireOwner(userId, storeId);
        checkRate(userId);

        LocalDate today = TimeUtil.today();
        List<StoreWorker> active = active(storeId);
        LaborContext.StoreFacts facts = facts(store, active.size());
        Map<Integer, String> restore = new HashMap<>();
        List<LaborContext.Staff> staff = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            restore.put(i + 1, active.get(i).getWorker().getName());
            staff.add(staff(active.get(i), "직원" + (i + 1), 0, today));
        }
        // 이번 주 월요일 ~ 다음 달 말일이 든 주의 일요일 (주 단위 규칙이 온전한 주를 보게)
        LocalDate from = TimeUtil.monday(today);
        LocalDate to = today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        List<FixedShiftPlanner.Planned> shifts = scheduleRepository.findByStoreBetween(storeId, from, to).stream()
                .filter(Schedule::isWork)
                .map(s -> new FixedShiftPlanner.Planned(s.getStoreWorker().getId(), s.getWorkDate(), s.getStartMin(),
                        s.endAbs(), s.getPart(), s.getBreakMin()))
                .toList();
        List<AiDto.Finding> findings = LaborAudit.run(facts, staff, shifts, today);
        if (findings.isEmpty()) {
            return new AiDto.AuditResponse("점검한 항목에서 문제를 찾지 못했어요.", false, LocalDateTime.now(), List.of());
        }

        String summary = defaultSummary(findings);
        boolean aiUsed = false;
        if (llm.enabled()) {
            try {
                JsonNode res = MAPPER.readTree(llm.chat(List.of(
                        Map.of("role", "system", "content", AUDIT_SYSTEM),
                        Map.of("role", "user", "content", auditPrompt(findings))), auditSchema(), NUM_CTX, true));
                String s = res.path("summary").asString("").trim();
                if (!s.isEmpty()) {
                    Map<String, String> actions = new HashMap<>();
                    for (JsonNode it : res.path("items")) {
                        String a = it.path("action").asString("").trim();
                        if (!a.isEmpty()) actions.put(it.path("code").asString(""), a);
                    }
                    findings = findings.stream()
                            .map(f -> f.withText(f.detail(), actions.getOrDefault(f.code(), f.action()))).toList();
                    summary = s;
                    aiUsed = true;
                }
            } catch (Exception ignored) {
                // AI 가 꺼졌거나 실패해도 규칙 결과는 돌려준다(본문·키는 로그에 남기지 않는다).
            }
        }
        List<AiDto.Finding> named = findings.stream().map(f -> f.withText(
                ScheduleAiService.nameBack(f.detail(), ALIAS, restore),
                ScheduleAiService.nameBack(f.action(), ALIAS, restore))).toList();
        return new AiDto.AuditResponse(ScheduleAiService.nameBack(summary, ALIAS, restore), aiUsed,
                LocalDateTime.now(), named);
    }

    static final String AUDIT_SYSTEM = "당신은 한국 소상공인 매장 사장님을 돕는 노무 도우미예요. "
            + "사용자 메시지는 규칙으로 계산한 노무 점검 결과예요. "
            + "summary 에는 가장 급한 것부터 2~3문장으로 요약하고, items 에는 결과의 code 마다 사장님이 할 일을 한두 문장(action)으로 적으세요. "
            + "직원은 별칭(직원1 등)으로만 부르고, 숫자·날짜는 바꾸지 말고, 결과에 없는 내용은 지어내지 마세요. 존댓말(~해요)로 쓰세요.";

    static String auditPrompt(List<AiDto.Finding> findings) {
        StringBuilder sb = new StringBuilder("## 노무 점검 결과\n");
        for (AiDto.Finding f : findings) {
            sb.append("- code=").append(f.code()).append(", 심각도=").append(f.severity())
                    .append(", ").append(f.title()).append(": ").append(f.detail())
                    .append(" / 기본 조치: ").append(f.action()).append('\n');
        }
        return sb.toString();
    }

    static String defaultSummary(List<AiDto.Finding> findings) {
        long high = findings.stream().filter(f -> LaborAudit.HIGH.equals(f.severity())).count();
        long medium = findings.stream().filter(f -> LaborAudit.MEDIUM.equals(f.severity())).count();
        long low = findings.size() - high - medium;
        return "노무 점검에서 " + findings.size() + "건을 찾았어요(높음 " + high + " · 보통 " + medium + " · 낮음 " + low
                + "). 높음부터 확인해 주세요.";
    }

    static Map<String, Object> auditSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "string", "description", "사장님께 드리는 2~3문장 요약"),
                        "items", Map.of("type", "array", "items", Map.of("type", "object",
                                "properties", Map.of("code", Map.of("type", "string"), "action", Map.of("type", "string")),
                                "required", List.of("code", "action")))),
                "required", List.of("summary", "items"));
    }

    // ---------------------------------------------------------------- 데이터

    private List<StoreWorker> active(Integer storeId) {
        return storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .sorted(Comparator.comparing(StoreWorker::getId)).toList();
    }

    private static LaborContext.StoreFacts facts(Store store, int activeCount) {
        return new LaborContext.StoreFacts(store.effectiveUnderFive(activeCount), store.getSizeMode(),
                store.getOpenMin(), store.getCloseMin(), store.getWeekendOpenMin(), store.getWeekendCloseMin(), activeCount);
    }

    private LaborContext.Staff staff(StoreWorker sw, String label, int avgWeeklyMin, LocalDate today) {
        int wage = StaffWageService.wageOn(staffWageService.rows(sw.getId()), today,
                sw.getHourlyWage() == null ? 0 : sw.getHourlyWage());
        Contract c = contractRepository.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId()).orElse(null);
        LaborContext.ContractInfo ci = c == null ? null
                : new LaborContext.ContractInfo(c.getOwnerSignedAt() != null, c.getEmployeeSignedAt() != null, c.getWeeklyHours());
        return new LaborContext.Staff(sw.getId(), label, sw.getKind(), wage, sw.getTaxType(), sw.isWeeklyHolidayAuto(),
                sw.isPremium(), avgWeeklyMin, ci, sw.getWorker().getCertExpiry());
    }

    /** 이번 달 근무 분 → 주 평균 분. */
    private static int avgWeekly(int monthMin, LocalDate lastOfMonth) {
        return Math.round(monthMin * 7f / lastOfMonth.getDayOfMonth());
    }

    /** 2자 이상 이름만 가린다(한 글자는 문장의 다른 글자까지 바꿔 버린다). */
    private static void putNames(Map<String, String> mask, User u, String alias) {
        if (u == null) return;
        for (String n : new String[]{u.getName(), u.getFullName()}) {
            if (n != null && n.trim().length() >= 2) mask.putIfAbsent(n.trim(), alias);
        }
    }

    // ---------------------------------------------------------------- 제한

    private void requireEnabled() {
        if (!llm.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "AI 도우미가 아직 설정되지 않았어요");
        }
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
