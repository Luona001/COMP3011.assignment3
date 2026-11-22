package comp3011;

import java.util.List;

/**
 * Shared, thread-safe state between the subtitle fetcher thread, the
 * effect-stage thread that renders subtitles, and the FX thread that toggles
 * them. One process plays one media file, so a singleton keeps wiring simple.
 *
 * Concurrency: all mutable state is volatile / copy-on-write lists; readers
 * (effect stage) never lock, the fetcher writes once, the FX thread only
 * flips `visible`. Toggling visible therefore never blocks or restarts the
 * pipeline - playback continuity is preserved by construction.
 */
public final class SubtitleTrack {

    public enum Status { LOADING, READY, UNAVAILABLE }

    /** One on-screen phrase. Times are media timestamps in microseconds. */
    public record Cue(long startUs, long endUs, List<String> lines) {
    }

    private static final SubtitleTrack INSTANCE = new SubtitleTrack();

    public static SubtitleTrack get() {
        return INSTANCE;
    }

    private volatile Status status = Status.LOADING;
    private volatile List<Cue> cues = List.of();
    private volatile boolean visible = true;

    private SubtitleTrack() {
    }

    public Status status() {
        return status;
    }

    public boolean isVisible() {
        return visible;
    }
    
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** Returns the new visible state so callers can update the button icon. */
    public boolean toggleVisible() {
        visible = !visible;
        return visible;
    }

    public void setCues(List<Cue> newCues) {
        cues = List.copyOf(newCues);
        status = Status.READY;
    }

    public void markUnavailable() {
        status = Status.UNAVAILABLE;
    }

    /** Called once at launch so a fresh session starts clean. */
    public void reset() {
        status = Status.LOADING;
        cues = List.of();
        visible = true;
    }

    /**
     * Cue covering the given media timestamp, or null. Uses binary search and
     * picks the latest-starting cue that still contains the timestamp, so it
     * behaves correctly after arbitrary seeking.
     */
    public Cue cueAt(long timestampUs) {
        List<Cue> list = cues;
        int lo = 0;
        int hi = list.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).startUs() <= timestampUs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) {
            return null;
        }
        Cue cue = list.get(found);
        return timestampUs < cue.endUs() ? cue : null;
    }
}