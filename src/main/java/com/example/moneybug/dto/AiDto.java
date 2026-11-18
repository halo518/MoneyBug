package com.example.moneybug.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

public class AiDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotBlank(message = "질문을 입력해 주세요")
        // 글자 수(1000자) 검사는 AiLaborService 가 FE 처럼 글자(그래핌) 단위로 한다. 여기는 터무니없는 크기만 막는다.
        @Size(max = 20000, message = "질문은 1000자까지 쓸 수 있어요")
        private String question;
    }

    @Getter
    @AllArgsConstructor
    public static class Response {
        private String answer;
        private LocalDateTime createdAt;
    }
}
