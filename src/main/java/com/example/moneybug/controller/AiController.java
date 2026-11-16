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

@Tag(name = "AI 노무 도우미", description = "Anthropic API 로 노무 질문에 답한다. 사용자당 시간당 20회")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiLaborService service;

    @Operation(summary = "노무 질문", description = "키 없음 503 AI_DISABLED, 호출 실패 502 AI_FAILED, 초과 429 AI_RATE_LIMIT. 권한: 사장님")
    @PostMapping("/labor-questions")
    public AiDto.Response ask(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                              @Valid @RequestBody AiDto.Request request) {
        return service.ask(userId.intValue(), storeId, request.getQuestion());
    }
}
