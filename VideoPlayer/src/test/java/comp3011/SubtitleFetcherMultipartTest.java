package comp3011;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SubtitleFetcherMultipartTest {

    @Test
    void multipartBodyUsesCorrectAudioFieldAndFilename() throws IOException {
        byte[] body = SubtitleFetcher.multipartBody(
                new byte[] { 9 },
                "audio.mp3",
                "audio/mpeg",
                "B");

        String s = new String(body, StandardCharsets.UTF_8);

        assertTrue(s.contains("name=\"audio\""));
        assertTrue(s.contains("filename=\"audio.mp3\""));
        assertTrue(s.contains("Content-Type: audio/mpeg"));
    }

    @Test
    void multipartBodySupportsWavContentType() throws IOException {
        byte[] body = SubtitleFetcher.multipartBody(
                new byte[] { 9 },
                "audio.wav",
                "audio/wav",
                "B");

        String s = new String(body, StandardCharsets.UTF_8);

        assertTrue(s.contains("name=\"audio\""));
        assertTrue(s.contains("filename=\"audio.wav\""));
        assertTrue(s.contains("Content-Type: audio/wav"));
    }
}
