package com.yrootlab.onmaru.journey.thread;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRedactorTests {

    @Test
    @DisplayName("일반적인 한국어 여행 쿼리는 마스킹 없이 원문을 보존한다")
    void normalQueryPreserved() {
        var query = "전주에서 조용하고 예쁜 한옥 산책로를 추천해줘";
        var result = QueryRedactor.redact(query);

        assertThat(result.hasRedactions()).isFalse();
        assertThat(result.redactedText()).isEqualTo(query);
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("이메일, 전화번호, 주민등록번호, 토큰이 포함된 쿼리는 마스킹 처리되고 flag를 기록한다")
    void sensitiveQueryRedacted() {
        var query = "연락처 010-1234-5678 이랑 test@naver.com, 주민번호 900101-1234567, Bearer secret-token-xyz 로 예약 확인해줘";
        var result = QueryRedactor.redact(query);

        assertThat(result.hasRedactions()).isTrue();
        assertThat(result.flags()).containsExactlyInAnyOrder("EMAIL", "PHONE", "SSN", "TOKEN");
        assertThat(result.redactedText()).doesNotContain("010-1234-5678");
        assertThat(result.redactedText()).doesNotContain("test@naver.com");
        assertThat(result.redactedText()).doesNotContain("900101-1234567");
        assertThat(result.redactedText()).doesNotContain("secret-token-xyz");
        assertThat(result.redactedText()).contains("[REDACTED_PHONE]");
        assertThat(result.redactedText()).contains("[REDACTED_EMAIL]");
        assertThat(result.redactedText()).contains("[REDACTED_SSN]");
        assertThat(result.redactedText()).contains("[REDACTED_TOKEN]");
    }
}
