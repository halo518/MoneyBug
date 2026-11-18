package com.example.moneybug.config;

import com.example.moneybug.service.StoreChangeHub;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * /stores/{storeId}/** 에 성공한 쓰기 요청이 끝나면 그 매장에 변경 신호를 보낸다.
 * DB 를 바꾸지 않는 POST(미리보기·AI 질문)는 빼서, 매장 전원의 기기가 쓸데없이 다시 읽지 않게 한다.
 */
@Configuration
@RequiredArgsConstructor
public class StoreChangeInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String[] READ_ONLY_POSTS = {
            "/api/v1/stores/*/schedules/generate/preview",
            "/api/v1/stores/*/schedule-plans",
            "/api/v1/stores/*/ai/labor-questions",
    };

    private final StoreChangeHub hub;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/stores/**")
                .excludePathPatterns(READ_ONLY_POSTS);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if ("GET".equals(request.getMethod()) || ex != null || response.getStatus() >= 400) return;
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars)) return;
        if (vars.get("storeId") instanceof String id) {
            try {
                hub.bump(Integer.valueOf(id));
            } catch (NumberFormatException ignored) {
                // 숫자가 아닌 매장 id 는 컨트롤러가 이미 거절했다.
            }
        }
    }
}
