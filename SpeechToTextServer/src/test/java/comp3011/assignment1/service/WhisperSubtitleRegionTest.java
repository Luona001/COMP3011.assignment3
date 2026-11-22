package comp3011.assignment1.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhisperSubtitleRegionTest {

    private static byte[] wav(byte... pcm) {
        byte[] out = new byte[44 + pcm.length];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[28] = 0x10; out[29] = (byte) 0xB1; out[30] = 0x02; out[31] = 0x00;
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    @Test
    void prefixMatchReturnsHeaderOffset() {
        byte[] stored = wav((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
        byte[] requested = wav((byte) 1, (byte) 2, (byte) 3);

        assertEquals(44, WhisperSubtitleService.wavRegionOffset(stored, requested));
    }

    @Test
    void regionMatchInsideStoredReturnsExactOffset() {
        byte[] stored = wav((byte) 9, (byte) 8, (byte) 7, (byte) 1, (byte) 2, (byte) 3);
        byte[] requested = wav((byte) 1, (byte) 2, (byte) 3);

        assertEquals(47, WhisperSubtitleService.wavRegionOffset(stored, requested));
    }

    @Test
    void regionMissWhenPayloadNotContained() {
        byte[] stored = wav((byte) 1, (byte) 2, (byte) 9);
        byte[] requested = wav((byte) 1, (byte) 2, (byte) 3);

        assertEquals(-1, WhisperSubtitleService.wavRegionOffset(stored, requested));
    }

    @Test
    void regionMissWhenRequestedLongerThanStored() {
        byte[] stored = wav((byte) 1, (byte) 2);
        byte[] requested = wav((byte) 1, (byte) 2, (byte) 3);

        assertEquals(-1, WhisperSubtitleService.wavRegionOffset(stored, requested));
    }

    @Test
    void regionMissWhenNotWav() {
        byte[] raw = new byte[50];
        assertEquals(-1, WhisperSubtitleService.wavRegionOffset(raw, new byte[] { 1, 2, 3 }));
    }

    @Test
    void shiftSecondsUsesStoredByteRate() {
        byte[] stored = wav(new byte[176400]);

        assertEquals(0.0, WhisperSubtitleService.excerptShiftSeconds(stored, 44), 1e-9);
        assertEquals(1.0, WhisperSubtitleService.excerptShiftSeconds(stored, 44 + 176400), 1e-9);
    }

    @Test
    void shiftSegmentsMovesClampsAndDrops() throws Exception {
        String result = "{\"duration\":10.0,\"text\":\"x\","
                + "\"segments\":["
                + "{\"start\":6.0,\"end\":7.0,\"text\":\"a\"},"
                + "{\"start\":1.0,\"end\":4.0,\"text\":\"b\"},"
                + "{\"start\":3.0,\"end\":9.0,\"text\":\"c\"}],"
                + "\"words\":[]}";

        String shifted = WhisperSubtitleService.shiftSegments(result, 5.0);

        assertTrue(shifted.contains("\"start\":1.0"));
        assertTrue(shifted.contains("\"end\":2.0"));
        assertFalse(shifted.contains("\"text\":\"b\""));
        assertTrue(shifted.contains("\"start\":0.0"));
        assertTrue(shifted.contains("\"end\":4.0"));
    }

    @Test
    void shiftZeroKeepsJsonUntouched() throws Exception {
        String result = "{\"duration\":10.0,\"text\":\"x\","
                + "\"segments\":[{\"start\":6.0,\"end\":7.0,\"text\":\"a\"}],"
                + "\"words\":[]}";

        assertEquals(result, WhisperSubtitleService.shiftSegments(result, 0.0));
    }
}