package comp3011;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VideoPlayerModelDrainTest {

    @Test
    void waitsForGracePeriodEvenWhenQueuesLookEmpty() {
        assertFalse(VideoPlayerModel.shouldCompleteDrain(true, 50_000_000L));
        assertTrue(VideoPlayerModel.shouldCompleteDrain(true, 250_000_000L));
    }

    @Test
    void keepsDrainingWhileFramesRemainUntilTimeout() {
        assertFalse(VideoPlayerModel.shouldCompleteDrain(false, 250_000_000L));
        assertTrue(VideoPlayerModel.shouldCompleteDrain(false, 2_000_000_000L));
    }
}