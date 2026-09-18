package com.yrootlab.onmaru.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OnMaru Backend REST API")
                        .version("v1.0.0")
                        .description("OnMaru 전통 한옥 아카이브, 관광지 큐레이션, Odii 오디오 도슨트, 행정구역 지도 방문 후기, AI 여정 탐색 및 개인 저장소 API 명세서")
                        .contact(new Contact()
                                .name("OnMaru Backend Team")
                                .url("https://github.com/YRootLab/OnMaru-backend"))
                        .license(new License().name("Apache 2.0").url("https://springdoc.org")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("cookieAuth")
                        .addList("csrfToken"))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("회원 세션 쿠키 인증"))
                        .addSecuritySchemes("csrfToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-CSRF-TOKEN")
                                .description("CSRF 방어 토큰 (/auth/csrf 에서 발급)"))
                        .addSecuritySchemes("internalSecret", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Token")
                                .description("내부 마이크로서비스 간 인증 토큰")));
    }

    @Bean
    public GroupedOpenApi allApis() {
        return GroupedOpenApi.builder()
                .group("00. 전체 API (All APIs)")
                .pathsToMatch("/api/**", "/auth/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hanokAndPlaceApis() {
        return GroupedOpenApi.builder()
                .group("01. 한옥 & 장소 (Hanok & Place)")
                .pathsToMatch("/api/v1/hanoks/**", "/api/v1/places/**", "/api/v1/editorial/**")
                .build();
    }

    @Bean
    public GroupedOpenApi odiiAudioApis() {
        return GroupedOpenApi.builder()
                .group("02. 오디 오디오 도슨트 (Odii Audio)")
                .pathsToMatch("/api/v1/audio/**")
                .build();
    }

    @Bean
    public GroupedOpenApi mapAndReviewApis() {
        return GroupedOpenApi.builder()
                .group("03. 지도 & 방문 후기 (Map & Reviews)")
                .pathsToMatch("/api/v1/visit-reviews/**", "/api/v1/visit-review-regions/**")
                .build();
    }

    @Bean
    public GroupedOpenApi journeyAndAiApis() {
        return GroupedOpenApi.builder()
                .group("04. AI 여정 탐색 (Journey & AI)")
                .pathsToMatch("/api/v1/explorations/**", "/api/v1/saved-journeys/**", "/api/v1/me/journey-threads/**")
                .build();
    }

    @Bean
    public GroupedOpenApi memberAndSavedApis() {
        return GroupedOpenApi.builder()
                .group("05. 개인화 & 타임라인 (Saved & Timeline)")
                .pathsToMatch("/api/v1/saved-resources/**", "/api/v1/me/timeline/**", "/api/v1/members/**", "/auth/**")
                .build();
    }
}
