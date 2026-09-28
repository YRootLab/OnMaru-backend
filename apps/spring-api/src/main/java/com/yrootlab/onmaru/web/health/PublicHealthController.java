package com.yrootlab.onmaru.web.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "99. 운영 상태 (Health)", description = "서버 liveness 확인 API")
@RestController
public final class PublicHealthController {

    @Operation(
            summary = "서버 상태 확인",
            description = "외부 저장소에 의존하지 않고 애플리케이션의 liveness를 확인합니다.")
    @ApiResponse(responseCode = "200", description = "서버가 요청을 처리할 수 있음")
    @GetMapping("/api/v1/health")
    PublicHealthResponse health() {
        return PublicHealthResponse.up();
    }
}
