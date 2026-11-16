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

/** /stores/{storeId}/** 에 성공한 쓰기 요청이 끝나면 그 매장에 변경 신호를 보낸다. */
@Configuration
@RequiredArgsConstructor
public class StoreChangeInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final StoreChangeHub hub;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/stores/**");
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
