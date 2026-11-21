package comp3011;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubtitleTrackTest {

    @AfterEach
    void reset() {
        SubtitleTrack.get().reset();
    }

    @Test
    void cueLookupRespectsTimeWindow() {
        SubtitleTrack track = SubtitleTrack.get();
        track.setCues(List.of(
                new SubtitleTrack.Cue(0, 2_000_000, List.of("One")),
                new SubtitleTrack.Cue(4_000_000, 6_000_000, List.of("Two"))));

        assertEquals("One", track.cueAt(1_000_000).lines().get(0));
        assertNull(track.cueAt(3_000_000));
        assertEquals("Two", track.cueAt(5_000_000).lines().get(0));
        assertNull(track.cueAt(9_000_000));
    }

    @Test
    void cueLookupWorksAfterSeekBackwards() {
        SubtitleTrack track = SubtitleTrack.get();
        track.setCues(List.of(
                new SubtitleTrack.Cue(0, 2_000_000, List.of("One")),
                new SubtitleTrack.Cue(10_000_000, 12_000_000, List.of("Two"))));

        assertEquals("Two", track.cueAt(11_000_000).lines().get(0));
        assertEquals("One", track.cueAt(500_000).lines().get(0));
    }

    @Test
    void toggleVisibleFlipsStateAndStartsOn() {
        SubtitleTrack track = SubtitleTrack.get();
        assertTrue(track.isVisible());
        assertFalse(track.toggleVisible());
        assertFalse(track.isVisible());
        assertTrue(track.toggleVisible());
    }

    @Test
    void parseCuesAppliesOneToSevenSecondRule() throws Exception {
        String json = """
                {"duration":3.0,"text":"a b",
                 "segments":[
                   {"start":0.0,"end":0.3,"text":"a"},
                   {"start":10.0,"end":40.0,"text":"b"}],
                 "words":[]}
                """;
        List<SubtitleTrack.Cue> cues = SubtitleFetcher.parseCues(json);

        assertEquals(2, cues.size());
        assertEquals(1_000_000, cues.get(0).endUs() - cues.get(0).startUs());
        assertEquals(7_000_000, cues.get(1).endUs() - cues.get(1).startUs());
    }
}