package com.example.moneybug.service;

import com.example.moneybug.dto.AiDto;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiLaborServiceTest {

    static final String URL = "http://llm.test/api/chat";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int OWNER = 1, STAFF_USER = 102, STORE = 1;

    final StoreRepository storeRepository = mock(StoreRepository.class);
    final StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    final ContractRepository contracts = mock(ContractRepository.class);
    final ScheduleRepository schedules = mock(ScheduleRepository.class);
    final StaffWageService wages = mock(StaffWageService.class);
    final StoreAccess access = new StoreAccess(storeRepository, workers);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final Store store = new Store();
    final List<StoreWorker> active = new ArrayList<>();
    /** 마지막으로 LLM 에 보낸 본문. */
    final String[] sent = new String[1];

    AiLaborServiceTest() {
        User owner = user(OWNER, "사장김", null);
        store.setId(STORE);
        store.setEmployer(owner);
        when(storeRepository.findById(STORE)).thenReturn(Optional.of(store));
        when(workers.findByStoreIdAndStatus(STORE, StoreWorker.Status.ACTIVE)).thenReturn(active);
        when(workers.findFirstByStoreIdAndWorkerIdAndStatus(anyInt(), anyInt(), any())).thenReturn(Optional.empty());
        when(contracts.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
    }

    static User user(int id, String name, String fullName) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setFullName(fullName);
        return u;
    }

    StoreWorker staff(int id, String name, int wage) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        sw.setStore(store);
        sw.setWorker(user(100 + id, name, name + "전체"));
        sw.setHourlyWage(wage);
        active.add(sw);
        return sw;
    }

    AiLaborService svc(String baseUrl) {
        return new AiLaborService(access, workers, contracts, schedules, wages,
                new LlmClient(builder.build(), baseUrl, "test-model", "k"));
    }

    AiLaborService svc() {
        return svc("http://llm.test");
    }

    RequestMatcher capture() {
        return req -> sent[0] = ((MockClientHttpRequest) req).getBodyAsString();
    }

    static String reply(Object content) {
        String c = content instanceof String s ? s : JSON.writeValueAsString(content);
        return JSON.writeValueAsString(Map.of("message", Map.of("role", "assistant", "content", c)));
    }

    void expectReply(Object content) {
        server.expect(requestTo(URL)).andExpect(capture())
                .andRespond(withSuccess(reply(content), MediaType.APPLICATION_JSON));
    }

    // ---------------------------------------------------------------- 질문: 호출·응답

    @Test
    void sendsToLlmWithModelAndSchemaAndCleansCitations() {
        List<String> cites = new ArrayList<>(List.of(" 근로기준법   제55조 ", "", "근로기준법 제55조", "가".repeat(61)));
        for (int i = 1; i <= 10; i++) cites.add("근로기준법 제" + i + "조");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer k"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.think").value(false))
                .andExpect(jsonPath("$.format.properties.answer.type").value("string"))
                .andExpect(jsonPath("$.format.properties.citations.type").value("array"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("주휴수당?"))
                .andRespond(withSuccess(reply(Map.of("answer", " 네, 줘야 해요. ", "citations", cites)),
                        MediaType.APPLICATION_JSON));
        AiDto.Response r = svc().ask(OWNER, STORE, "주휴수당?");
        server.verify();
        assertEquals("네, 줘야 해요.", r.getAnswer());
        assertEquals(List.of("근로기준법 제55조", "근로기준법 제1조", "근로기준법 제2조", "근로기준법 제3조",
                "근로기준법 제4조", "근로기준법 제5조", "근로기준법 제6조", "근로기준법 제7조"), r.getCitations());
    }

    @Test
    void nonJsonReplyIsTheAnswer() {
        expectReply("그냥 글로 답했어요.");
        AiDto.Response r = svc().ask(OWNER, STORE, "q");
        assertEquals("그냥 글로 답했어요.", r.getAnswer());
        assertEquals(List.of(), r.getCitations());
    }

    @Test
    void blankAnswerIsFailed() {
        expectReply(Map.of("answer", "  ", "citations", List.of("근로기준법 제55조")));
        assertEquals("AI_FAILED", assertThrows(ApiException.class, () -> svc().ask(OWNER, STORE, "q")).getCode());
        server.reset();
        expectReply("");
        ApiException e = assertThrows(ApiException.class, () -> svc().ask(OWNER, STORE, "q"));
        assertEquals("AI_FAILED", e.getCode());
        assertEquals(502, e.getStatus().value());
    }

    @Test
    void upstreamFailureIs502() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertEquals("AI_FAILED", assertThrows(ApiException.class, () -> svc().ask(OWNER, STORE, "q")).getCode());
    }

    @Test
    void blankBaseUrlIsDisabled() {
        ApiException e = assertThrows(ApiException.class, () -> svc(" ").ask(OWNER, STORE, "q"));
        assertEquals("AI_DISABLED", e.getCode());
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void rateLimitIsSharedWithAudit() {
        for (int i = 0; i < 10; i++) expectReply(Map.of("answer", "a", "citations", List.of()));
        AiLaborService s = svc();
        for (int i = 0; i < 10; i++) s.ask(OWNER, STORE, "q");
        for (int i = 0; i < 10; i++) s.audit(OWNER, STORE); // 직원 없음 → 문제 없음, LLM 호출 없음
        ApiException e = assertThrows(ApiException.class, () -> s.ask(OWNER, STORE, "q"));
        assertEquals("AI_RATE_LIMIT", e.getCode());
        assertEquals(429, e.getStatus().value());
        assertEquals("AI_RATE_LIMIT", assertThrows(ApiException.class, () -> s.audit(OWNER, STORE)).getCode());
        server.verify();
    }

    @Test
    void questionLengthCountsCharactersLikeFe() {
        // 이모지·가족 이모지(ZWJ)도 FE 처럼 1자. 1000자는 통과(LLM 꺼짐 503), 1001자는 한국어 400.
        assertEquals(1000, AiLaborService.length("😀".repeat(500) + "👨‍👩‍👧".repeat(500)));
        assertEquals("AI_DISABLED", assertThrows(ApiException.class, () -> svc("").ask(OWNER, STORE, "😀".repeat(1000))).getCode());
        ApiException e = assertThrows(ApiException.class, () -> svc("").ask(OWNER, STORE, "가".repeat(1001)));
        assertEquals("QUESTION_TOO_LONG", e.getCode());
        assertEquals("질문은 1000자까지 쓸 수 있어요", e.getMessage());
    }

    // ---------------------------------------------------------------- 사장님 맥락

    @Test
    void ownerContextUsesAliasesAndRestoresNames() {
        StoreWorker kim = staff(1, "김민수", 11000);
        kim.getWorker().setCertExpiry(LocalDate.now(TimeUtil.KST).plusDays(20));
        Contract c = new Contract();
        c.setOwnerSignedAt(LocalDateTime.now());
        c.setEmployeeSignedAt(LocalDateTime.now());
        c.setWeeklyHours(20);
        when(contracts.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(1)).thenReturn(Optional.of(c));
        staff(2, "김민", 12345);
        for (int i = 3; i <= 10; i++) staff(i, "이름" + (char) ('가' + i), 10320);
        Contract unsigned = new Contract();
        unsigned.setOwnerSignedAt(LocalDateTime.now());
        when(contracts.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(2)).thenReturn(Optional.of(unsigned));
        expectReply(Map.of("answer", "직원1님과 직원10님, 직원2님 모두 받아요. 사장님 확인해 주세요.",
                "citations", List.of("근로기준법 제55조")));

        AiDto.Response r = svc().ask(OWNER, STORE, "김민수랑 김민수전체, 김민, 사장김 중 누가 받아요?");

        String body = sent[0];
        assertTrue(body.contains("직원1랑 직원1, 직원2, 사장님 중 누가 받아요?"), body);
        assertTrue(body.contains("- 직원1: 알바, 시급 11,000원"), body);
        assertTrue(body.contains("근로계약서 서명 완료, 계약 주 20시간"), body);
        assertTrue(body.contains("보건증 D-20"), body);
        assertTrue(body.contains("- 직원2: 알바, 시급 12,345원"), body);
        assertTrue(body.contains("근로계약서 미서명(직원 서명 전)"), body);
        assertTrue(body.contains("- 직원10: "), body);
        assertTrue(body.contains("보건증 없음"), body);
        assertTrue(body.contains("5인 미만 사업장: 아니오"), body); // AUTO, 재직 10명
        for (StoreWorker sw : active) {
            assertFalse(body.contains(sw.getWorker().getName()), sw.getWorker().getName());
        }
        assertFalse(body.contains("사장김"));
        String tenth = active.get(9).getWorker().getName();
        assertEquals("김민수님과 " + tenth + "님, 김민님 모두 받아요. 사장님 확인해 주세요.", r.getAnswer());
        assertEquals(List.of("근로기준법 제55조"), r.getCitations());
    }

    @Test
    void ownerContextCapsAt50Lines() {
        for (int i = 1; i <= 53; i++) staff(i, "사람" + i + "번", 10320);
        expectReply(Map.of("answer", "a", "citations", List.of()));
        svc().ask(OWNER, STORE, "q");
        assertTrue(sent[0].contains("- 직원50: "));
        assertFalse(sent[0].contains("- 직원51: "));
        assertTrue(sent[0].contains("그 밖에 직원 3명은 생략했어요"));
    }

    // ---------------------------------------------------------------- 직원 맥락

    @Test
    void staffContextHasOnlyTheCallersLine() {
        staff(1, "박동료", 12345);
        StoreWorker me = staff(2, "최본인", 11111);
        when(workers.findFirstByStoreIdAndWorkerIdAndStatus(STORE, STAFF_USER, StoreWorker.Status.ACTIVE))
                .thenReturn(Optional.of(me));
        expectReply(Map.of("answer", "주휴수당을 받을 수 있어요.", "citations", List.of()));

        AiDto.Response r = svc().ask(STAFF_USER, STORE, "최본인인데 박동료보다 시급이 낮아요?");

        String body = sent[0];
        assertTrue(body.contains("- 질문자 본인: 알바, 시급 11,111원"), body);
        assertTrue(body.contains("본인인데 동료보다 시급이 낮아요?"), body);
        assertTrue(body.contains("직원의 편에서"), body);
        assertFalse(body.contains("12,345"), body);
        assertFalse(body.contains("박동료"), body);
        assertFalse(body.contains("최본인"), body);
        assertFalse(body.contains("직원1"), body);
        assertFalse(body.contains("직원2"), body);
        assertEquals("주휴수당을 받을 수 있어요.", r.getAnswer());
    }

    @Test
    void nonMemberOrInactiveStaffIsForbidden() {
        StoreWorker pending = staff(1, "대기중", 11000);
        pending.setStatus(StoreWorker.Status.PENDING);
        active.clear(); // PENDING·LEFT 는 ACTIVE 조회에 안 나온다
        for (int user : new int[]{99, 101}) {
            ApiException e = assertThrows(ApiException.class, () -> svc().ask(user, STORE, "q"));
            assertEquals(403, e.getStatus().value());
        }
        assertEquals(403, assertThrows(ApiException.class, () -> svc().audit(101, STORE)).getStatus().value());
        server.verify();
    }

    // ---------------------------------------------------------------- 점검

    @Test
    void auditUsesLlmSummaryAndRestoresNames() {
        staff(1, "김민수", 9000);
        expectReply(Map.of("summary", "직원1님 시급이 최저임금보다 낮아요.",
                "items", List.of(Map.of("code", "MIN_WAGE", "action", "직원1님 시급을 올리세요."),
                        Map.of("code", "NOPE", "action", "무시"))));

        AiDto.AuditResponse r = svc().audit(OWNER, STORE);

        assertTrue(r.aiUsed());
        assertEquals("김민수님 시급이 최저임금보다 낮아요.", r.summary());
        AiDto.Finding f = r.findings().stream().filter(x -> x.code().equals("MIN_WAGE")).findFirst().orElseThrow();
        assertEquals("김민수님 시급을 올리세요.", f.action());
        assertTrue(f.detail().contains("김민수 9,000원"), f.detail());
        assertEquals(List.of(1), f.staffIds());
        assertFalse(sent[0].contains("김민수"), sent[0]);
        assertTrue(sent[0].contains("code=MIN_WAGE"), sent[0]);
    }

    @Test
    void auditSurvivesLlmFailure() {
        staff(1, "김민수", 9000);
        server.expect(requestTo(URL)).andRespond(withServerError());
        AiDto.AuditResponse r = svc().audit(OWNER, STORE);
        assertFalse(r.aiUsed());
        assertTrue(r.summary().startsWith("노무 점검에서 "), r.summary());
        assertEquals("high", r.findings().get(0).severity());
        assertTrue(r.findings().stream().anyMatch(f -> f.code().equals("MIN_WAGE")
                && f.action().startsWith("시급을 10,320원 이상")));

        server.reset();
        expectReply("JSON 아님");
        assertFalse(svc().audit(OWNER, STORE).aiUsed());
        // LLM 꺼짐: 호출 없이 규칙 결과
        assertFalse(svc("").audit(OWNER, STORE).aiUsed());
    }

    @Test
    void auditWithoutFindingsSkipsLlm() {
        AiDto.AuditResponse r = svc().audit(OWNER, STORE); // 기대 요청 없음: 호출하면 실패
        server.verify();
        assertFalse(r.aiUsed());
        assertEquals(List.of(), r.findings());
        assertEquals("점검한 항목에서 문제를 찾지 못했어요.", r.summary());
    }
}
