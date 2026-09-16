package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.audio.sync.AudioRevisionSnapshot;
import com.yrootlab.onmaru.audio.sync.InMemoryAudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiSourceMapper;
import com.yrootlab.onmaru.audio.sync.OdiiSourceStory;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, OdiiStoryActiveRevisionWebBoundaryTests.RevisionTestConfiguration.class},
        properties = {
                "onmaru.secrets.source=fake",
                "onmaru.audio.public-hosts=cdn.onmaru.example"
        })
@AutoConfigureMockMvc
class OdiiStoryActiveRevisionWebBoundaryTests {

    private static final String STORY_ID = "odii-story-" + UUID.nameUUIDFromBytes(
            "KTO_ODII:odii-story-:562".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesTheActivePublishedRevisionWithoutAQueryStoreTestDouble() throws Exception {
        mockMvc.perform(get("/api/v1/odii/stories/{storyId}", STORY_ID)
                        .queryParam("language", "ko-KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.story.storyId").value(STORY_ID))
                .andExpect(jsonPath("$.story.title").value("전주 한옥마을"))
                .andExpect(jsonPath("$.audioUrl").value("https://cdn.onmaru.example/odii/story.mp3"))
                .andExpect(jsonPath("$.transcriptStatus").value("OFFICIAL"));
    }

    @TestConfiguration
    static class RevisionTestConfiguration {

        @Bean
        @Primary
        InMemoryAudioRevisionStore testAudioRevisionStore() {
            var mapped = new OdiiSourceMapper().map(new OdiiSourceStory(
                    "89",
                    "300",
                    "562",
                    "1204",
                    "전주 한옥마을",
                    "한옥 골목 이야기",
                    "골목에 남은 이야기를 들어보세요.",
                    "https://cdn.onmaru.example/odii/story.mp3",
                    "https://cdn.onmaru.example/odii/story.jpg",
                    "185",
                    "127.152948",
                    "35.817632",
                    "ko",
                    "20150619173503",
                    "20250609074606"));
            return new InMemoryAudioRevisionStore(
                    "odii-audio",
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    AudioRevisionSnapshot.from(List.of(mapped)),
                    new SourceWatermark(
                            "2026-09-15T02:00:00Z",
                            "562",
                            Instant.parse("2026-09-15T03:00:00Z")),
                    "worker-a",
                    1);
        }
    }
}
