package com.example.moneybug.dto;

import jakarta.validation.constraints.NotNull;

/** 대타 관련 추가 요청 (설계서 §4.7). 신청 본문은 SubstituteRequestDto.Request 를 쓴다. */
public class SubstituteCommandDto {

    /** POST .../assign — 사장님이 직접 대타자 지정. staffId = StoreWorker id */
    public record AssignRequest(@NotNull Integer staffId) {}
}
