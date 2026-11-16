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
        @NotBlank
        @Size(max = 1000)
        private String question;
    }

    @Getter
    @AllArgsConstructor
    public static class Response {
        private String answer;
        private LocalDateTime createdAt;
    }
}
