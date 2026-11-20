package comp3011.assignment1.controller;

import comp3011.assignment1.service.WhisperSubtitleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SubtitleController.class)
class SubtitleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WhisperSubtitleService subtitleService;

    private static final String SAMPLE =
            "{\"duration\":2.5,\"text\":\"Hello\","
            + "\"segments\":[{\"start\":0.0,\"end\":2.5,\"text\":\"Hello\"}],"
            + "\"words\":[{\"word\":\"Hello\",\"start\":0.0,\"end\":2.5}]}";

    @Test
    void subtitles_returnsSegmentJson() throws Exception {
        when(subtitleService.transcribeSubtitles(any())).thenReturn(SAMPLE);

        MockMultipartFile audio = new MockMultipartFile(
                "audio", "clip.mp3", "audio/mpeg", "fake audio".getBytes());

        mockMvc.perform(multipart("/api/v1/subtitles").file(audio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duration").value(2.5))
                .andExpect(jsonPath("$.segments[0].text").value("Hello"))
                .andExpect(jsonPath("$.segments[0].start").value(0.0))
                .andExpect(jsonPath("$.words[0].word").value("Hello"));
    }

    @Test
    void subtitles_serviceThrows_returns502() throws Exception {
        when(subtitleService.transcribeSubtitles(any()))
                .thenThrow(new RuntimeException("OpenAI down"));

        MockMultipartFile audio = new MockMultipartFile(
                "audio", "clip.mp3", "audio/mpeg", "fake audio".getBytes());

        mockMvc.perform(multipart("/api/v1/subtitles").file(audio))
                .andExpect(status().isBadGateway());
    }
}