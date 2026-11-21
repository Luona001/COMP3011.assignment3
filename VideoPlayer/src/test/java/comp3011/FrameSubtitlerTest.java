package comp3011;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameSubtitlerTest {

    @AfterEach
    void reset() {
        SubtitleTrack.get().reset();
    }

    private Frame newBlackFrame() {
        Frame frame = new Frame(320, 240, Frame.DEPTH_UBYTE, 3);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < pixels.capacity(); i++) {
            pixels.put(i, (byte) 0);
        }
        return frame;
    }

    private FrameSubtitler newProcessor() throws Exception {
        FrameSubtitler processor = new FrameSubtitler();
        processor.initialise(new InfoVideo(
                "test", 1, 320, 240, Frame.DEPTH_UBYTE, 3, 320 * 3,
                25.0, 25, 40_000, 0));
        return processor;
    }

    private int countNonBlackPixels(Frame frame) {
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        int count = 0;
        for (int i = 0; i < pixels.capacity(); i++) {
            if (pixels.get(i) != 0) {
                count++;
            }
        }
        return count;
    }

    @Test
    void drawsWhiteTextWhenCueIsActive() throws Exception {
        SubtitleTrack.get().setCues(List.of(
                new SubtitleTrack.Cue(0, 2_000_000, List.of("Hello"))));
        Frame frame = newBlackFrame();

        newProcessor().process(frame, new InfoFrame(1, 1_000_000));

        assertTrue(countNonBlackPixels(frame) > 0, "subtitle should paint pixels");
    }

    @Test
    void leavesFrameUntouchedWhenToggledOff() throws Exception {
        SubtitleTrack.get().setCues(List.of(
                new SubtitleTrack.Cue(0, 2_000_000, List.of("Hello"))));
        SubtitleTrack.get().toggleVisible();
        Frame frame = newBlackFrame();

        newProcessor().process(frame, new InfoFrame(1, 1_000_000));

        assertEquals(0, countNonBlackPixels(frame));
    }

    @Test
    void leavesFrameUntouchedWhenNoCueMatches() throws Exception {
        SubtitleTrack.get().setCues(List.of(
                new SubtitleTrack.Cue(0, 1_000_000, List.of("Hello"))));
        Frame frame = newBlackFrame();

        newProcessor().process(frame, new InfoFrame(1, 5_000_000));

        assertEquals(0, countNonBlackPixels(frame));
    }
}