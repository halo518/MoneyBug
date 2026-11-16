package com.example.moneybug.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Swagger UI 설정. api_design.md §1.2 의 'Authorization: Bearer <accessToken>' 을 UI 의 Authorize 버튼으로
 * 넣어 테스트할 수 있게 SecurityScheme(HTTP Bearer, JWT)을 등록하고 모든 API 에 기본 적용한다.
 *
 * 토큰 얻는 법: POST /api/auth/kakao/token 으로 로그인한 응답의 accessToken 을 Authorize 창에 붙여넣는다
 * (앞의 "Bearer " 는 자동으로 붙으므로 토큰 값만 입력).
 */
@Configuration
public class SwaggerConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI moneyBugOpenAPI() {
        // 컨트롤러의 @AuthenticationPrincipal Long userId 는 JWT 에서 채워지므로 문서의 입력 파라미터에서 뺀다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthenticationPrincipal.class);

        return new OpenAPI()
                .info(new Info()
                        .title("돈벌래(MoneyBug) 백엔드 API")
                        .description("알바생·사장님 근무/급여 관리 API. 설계서: api_design.md. "
                                + "우측 상단 Authorize 에 로그인으로 받은 accessToken 을 넣으면 인증이 필요한 API 를 호출할 수 있어요.")
                        .version("v1"))
                .addServersItem(new Server().url("/").description("현재 서버"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authorization: Bearer <accessToken>")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
