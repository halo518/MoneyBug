package com.example.moneybug.config;

import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 요청 JSON 의 모든 문자열에서 제어문자(NUL·BEL 등)를 지운다. 줄바꿈(\n)과 탭은 둔다(공지 본문은 여러 줄).
 * 이름·사유가 화면·공유 문구·알림·AI 프롬프트에서 깨지지 않게 (QA 2026-11-17 #02, #23).
 */
@Component
public class RequestStringModule extends SimpleModule {

    public RequestStringModule() {
        super("RequestStringModule");
        addDeserializer(String.class, new StringDeserializer() {
            @Override
            public String deserialize(JsonParser p, DeserializationContext ctxt) {
                return strip(super.deserialize(p, ctxt));
            }
        });
    }

    static String strip(String s) {
        if (s == null || s.chars().noneMatch(RequestStringModule::isDropped)) return s;
        StringBuilder b = new StringBuilder(s.length());
        s.chars().filter(c -> !isDropped(c)).forEach(c -> b.append((char) c));
        return b.toString();
    }

    private static boolean isDropped(int c) {
        return Character.isISOControl(c) && c != '\n' && c != '\t';
    }
}
