package com.example.moneybug.service;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 노무 도우미에 보낼 매장·직원 맥락 문장. DB 를 읽지 않는다(AiLaborService 가 읽어 값만 넘긴다).
 * 이름은 절대 넣지 않는다: 사장님 질문은 "직원1" 같은 별칭, 직원 질문은 "질문자 본인" 한 줄뿐.
 */
public final class LaborContext {

    static final int MAX_LINES = 50;

    private LaborContext() {}

    public record StoreFacts(boolean underFive, Store.SizeMode sizeMode, int openMin, int closeMin,
                             int weekendOpenMin, int weekendCloseMin, int activeCount) {}

    /** 현재 계약서. 없으면 Staff.contract = null. */
    public record ContractInfo(boolean ownerSigned, boolean employeeSigned, Integer weeklyHours) {
        boolean signed() { return ownerSigned && employeeSigned; }
    }

    /** label = 별칭(직원1…) 또는 "질문자 본인". wage = 오늘 적용 시급. avgWeeklyMin = 이번 달 근무(휴게 제외)의 주 평균. */
    public record Staff(int id, String label, StoreWorker.Kind kind, int wage, StoreWorker.TaxType deduction,
                        boolean weeklyHolidayAuto, boolean premium, int avgWeeklyMin, ContractInfo contract,
                        LocalDate certExpiry) {
        boolean minor() { return kind == StoreWorker.Kind.MINOR; }
    }

    // ---------------------------------------------------------------- 맥락

    /** omitted = 읽지 않고 뺀 직원 수(줄 수 제한). staff 가 MAX_LINES 를 넘어도 잘라서 그만큼 더한다. */
    static String ownerContext(StoreFacts store, List<Staff> staff, int omitted, LocalDate today) {
        StringBuilder sb = new StringBuilder(storeBlock(store));
        sb.append("\n[직원 정보] 이름 대신 별칭(직원1, 직원2 …)으로 적었어요. 답할 때도 별칭으로 부르세요.\n");
        if (staff.isEmpty() && omitted == 0) sb.append("- 재직 중인 직원이 없어요.\n");
        staff.stream().limit(MAX_LINES).forEach(s -> sb.append(line(s, today)).append('\n'));
        int left = omitted + Math.max(0, staff.size() - MAX_LINES);
        if (left > 0) sb.append("- 그 밖에 직원 ").append(left).append("명은 생략했어요.\n");
        return sb.toString();
    }

    static String staffContext(StoreFacts store, Staff me, LocalDate today) {
        return storeBlock(store) + "\n[질문자 정보] 질문한 직원 본인의 정보만 있어요.\n" + line(me, today) + '\n';
    }

    private static String storeBlock(StoreFacts s) {
        String mode = switch (s.sizeMode()) {
            case AUTO -> "자동(재직 인원 기준)";
            case UNDER5 -> "사장님이 5인 미만으로 직접 설정";
            case OVER5 -> "사장님이 5인 이상으로 직접 설정";
        };
        return "[매장 정보]\n"
                + "- 5인 미만 사업장: " + (s.underFive() ? "예" : "아니오") + " (규모 설정: " + mode + ")\n"
                + "- 영업시간: 평일 " + hours(s.openMin(), s.closeMin()) + ", 주말 " + hours(s.weekendOpenMin(), s.weekendCloseMin()) + "\n"
                + "- 재직 인원: " + s.activeCount() + "명\n";
    }

    static String line(Staff s, LocalDate today) {
        return "- " + s.label() + ": " + kind(s.kind())
                + ", 시급 " + won(s.wage())
                + ", 공제 " + deduction(s.deduction())
                + ", 주휴수당 자동 " + (s.weeklyHolidayAuto() ? "켬" : "끔")
                + ", 가산수당 " + (s.premium() ? "켬" : "끔")
                + ", 이번 달 주 평균 근무 " + hours(s.avgWeeklyMin()) + "(휴게 제외)"
                + ", " + contract(s.contract())
                + ", " + cert(s.certExpiry(), today);
    }

    static String kind(StoreWorker.Kind k) {
        if (k == null) return "알바";
        return switch (k) {
            case MANAGER -> "매니저";
            case FULLTIME -> "정직원";
            case PART_TIMER -> "알바";
            case MINOR -> "연소자";
        };
    }

    static String deduction(StoreWorker.TaxType t) {
        if (t == null) return "없음";
        return switch (t) {
            case BUSINESS_TAX -> "3.3% 원천징수";
            case FOUR_INSURANCE -> "4대보험";
            case NONE -> "없음";
        };
    }

    static String contract(ContractInfo c) {
        if (c == null) return "근로계약서 없음";
        String weekly = c.weeklyHours() == null ? "" : ", 계약 주 " + c.weeklyHours() + "시간";
        if (c.signed()) return "근로계약서 서명 완료" + weekly;
        String who = !c.ownerSigned() && !c.employeeSigned() ? "사장님·직원 모두"
                : !c.ownerSigned() ? "사장님" : "직원";
        return "근로계약서 미서명(" + who + " 서명 전)" + weekly;
    }

    static String cert(LocalDate expiry, LocalDate today) {
        if (expiry == null) return "보건증 없음";
        long d = ChronoUnit.DAYS.between(today, expiry);
        return d < 0 ? "보건증 만료" : "보건증 D-" + d;
    }

    static String won(int v) {
        return String.format("%,d원", v);
    }

    /** 분 → "15시간" / "15.5시간". */
    static String hours(int min) {
        return min % 60 == 0 ? min / 60 + "시간" : String.format("%.1f시간", min / 60.0);
    }

    private static String hours(int open, int close) {
        return TimeUtil.hhmm(open) + "~" + (close >= 1440 ? "익일 " : "") + TimeUtil.hhmm(close);
    }

    // ---------------------------------------------------------------- 시스템 프롬프트

    private static final String RULES = "한국 근로기준법 등 노동법 기준으로, 아래 매장 정보를 맥락으로 답하세요. "
            + "짧고 실용적으로, 존댓말(~해요)로 답하세요. 법 조항은 확실할 때만 적고, 모르면 적지 마세요. "
            + "아래 정보에 없는 숫자(시급·시간·금액 등)는 지어내지 마세요. "
            + "확실하지 않으면 노무사나 고용노동부 고객상담센터(국번 없이 1350)에 확인하라고 권하세요. "
            + "답의 마지막 줄에는 이 답이 법률 자문이 아니라 참고용 정보라는 말을 짧게 붙이세요. "
            + "응답은 JSON {\"answer\": 답변, \"citations\": [근거 조항, 예: \"근로기준법 제55조\"]} 로만 하세요.";

    static String ownerSystem(String context) {
        return "당신은 5인 안팎의 소상공인 매장 사장님을 돕는 노무 도우미예요. " + RULES + "\n\n" + context;
    }

    static String staffSystem(String context) {
        return "당신은 소상공인 매장에서 일하는 직원(알바생)을 돕는 노무 도우미예요. 직원의 편에서 "
                + "주휴수당, 휴게시간, 최저임금, 근로계약서, 해고 예고 같은 직원의 권리를 쉽게 설명하세요. "
                + "당신은 질문한 직원 본인의 정보만 알고 있고, 다른 직원의 정보는 모른다고 답하세요. "
                + RULES + "\n\n" + context;
    }

    // ---------------------------------------------------------------- 이름 가리기

    /** text 의 이름(키)을 별칭(값)으로 한 번에 바꾼다. 긴 이름부터 맞춰서 "김민수" 가 "김민" 보다 먼저 잡힌다. */
    static String mask(String text, Map<String, String> nameToAlias) {
        if (text == null || nameToAlias.isEmpty()) return text;
        Pattern p = Pattern.compile(nameToAlias.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote).collect(Collectors.joining("|")));
        return p.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(nameToAlias.get(m.group())));
    }
}
