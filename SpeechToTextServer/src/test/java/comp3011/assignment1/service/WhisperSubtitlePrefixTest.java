
package comp3011.assignment1.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhisperSubtitlePrefixTest {

    @Test
    void prefixHitWhenRequestedAudioIsPrefixOfCachedAudio() {
        byte[] candidate = { 1, 2, 3, 4, 5 };
        byte[] requested = { 1, 2, 3 };

        assertTrue(
                WhisperSubtitleService.prefixHit(candidate, requested));
    }

    @Test
    void prefixMissWhenBytesDoNotMatch() {
        byte[] candidate = { 1, 2, 3, 4, 5 };
        byte[] requested = { 1, 2, 9 };

        assertFalse(
                WhisperSubtitleService.prefixHit(candidate, requested));
    }

    @Test
    void prefixMissWhenRequestedAudioIsLongerThanCachedAudio() {
        byte[] candidate = { 1, 2, 3 };
        byte[] requested = { 1, 2, 3, 4, 5 };

        assertFalse(
                WhisperSubtitleService.prefixHit(candidate, requested));
    }
}
