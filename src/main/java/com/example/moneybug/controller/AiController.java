package com.example.moneybug.controller;

import com.example.moneybug.dto.AiDto;
import com.example.moneybug.service.AiLaborService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI 노무 도우미", description = "자체 LLM 서버(app.llm)로 노무 질문·점검. 사용자당 시간당 20회(질문·점검 합산)")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiLaborService service;

    @Operation(summary = "노무 질문", description = "LLM 주소 없음 503 AI_DISABLED, 호출 실패 502 AI_FAILED, 초과 429 AI_RATE_LIMIT, "
            + "1000자 초과 400 QUESTION_TOO_LONG. 권한: 사장님·재직 직원(직원은 본인 정보만 AI 에 간다)")
    @PostMapping("/labor-questions")
    public AiDto.Response ask(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                              @Valid @RequestBody AiDto.Request request) {
        return service.ask(userId.intValue(), storeId, request.getQuestion());
    }

    @Operation(summary = "노무 점검", description = "규칙으로 찾은 문제 + AI 요약. AI 가 꺼졌거나 실패해도 200(aiUsed=false). "
            + "초과 429 AI_RATE_LIMIT. 권한: 사장님")
    @PostMapping("/labor-audit")
    public AiDto.AuditResponse audit(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        return service.audit(userId.intValue(), storeId);
    }
}
