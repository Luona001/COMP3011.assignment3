package comp3011.assignment1.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhisperSubtitleFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void badSpeechSegmentsAreFilteredOut() throws Exception {
        String whisperResponse = """
                {"duration":10.0,"text":"Hi",
                 "segments":[
                   {"start":0.0,"end":2.0,"text":"Hi","no_speech_prob":0.1,"avg_logprob":-0.2},
                   {"start":2.0,"end":4.0,"text":"noise","no_speech_prob":0.9,"avg_logprob":-1.5},
                   {"start":4.0,"end":6.0,"text":"there","no_speech_prob":0.9,"avg_logprob":-0.5},
                   {"start":6.0,"end":8.0,"text":"noise2","no_speech_prob":0.2,"avg_logprob":-1.5}],
                 "words":[{"word":"Hi","start":0.0,"end":2.0}]}
                """;

        String result = WhisperSubtitleService.filterAndShape(
                mapper.readTree(whisperResponse));
        JsonNode out = mapper.readTree(result);
        assertEquals(3, out.path("segments").size());
        assertEquals("Hi", out.path("segments").get(0).path("text").asText());
        assertEquals("there", out.path("segments").get(1).path("text").asText());
        assertEquals(10.0, out.path("duration").asDouble(), 0.001);
        assertEquals(1, out.path("words").size());
    }
}