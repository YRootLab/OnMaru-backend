package com.yrootlab.onmaru;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "onmaru.secrets.source=fake")
class OnMaruApplicationTests {

    @Test
    void startsWithoutExternalCredentials() {
    }
}
