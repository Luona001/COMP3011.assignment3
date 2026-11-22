package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads subtitles for a media file on a background daemon thread so playback
 * is never blocked:
 *
 *   1. local disk cache (~/.comp3011/subtitles) - survives restarts, keeps
 *      subtitles working during a network outage if seen before;
 *   2. else extract the original media audio to MP3 (WAV fallback),
 *      POST it to http://localhost:8080/api/v1/subtitles with retries;
 *   3. parse segments, apply the 1s/7s display rules, wrap lines, save the
 *      response to the disk cache and publish cues to SubtitleTrack.
 *
 * Any failure (missing file, server down, network down) marks the track
 * UNAVAILABLE and returns - playback continues unaffected.
 */
public final class SubtitleFetcher {

    private static final String ENDPOINT = "http://localhost:8080/api/v1/subtitles";
    private static final long MIN_DISPLAY_US = 1_000_000L;
    private static final long MAX_DISPLAY_US = 7_000_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Audio(byte[] data, String filename) {}
    
    private SubtitleFetcher() {
    }

    /** Starts an asynchronous load; returns immediately. */
    public static void start(File mediaFile, Consumer<String> status) {
        SubtitleTrack track = SubtitleTrack.get();
        track.reset();
        Thread worker = new Thread(() -> load(mediaFile, track, status), "subtitle-fetcher");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void load(File mediaFile, SubtitleTrack track, Consumer<String> status) {
        String name = shortName(mediaFile);
        try {
            Path cacheFile = cacheDir().resolve(cacheKey(mediaFile) + ".json");
            String body;
            if (Files.isRegularFile(cacheFile)) {
                body = Files.readString(cacheFile);
            } else {
            	Audio audio = extractAudio(mediaFile);
            	status.accept(name + " - transcribing subtitles...");
            	body = post(audio);
                try {
                    Files.createDirectories(cacheDir());
                    Files.writeString(cacheFile, body);
                } catch (IOException cacheError) {
                }
            }
            track.setCues(parseCues(body));
            status.accept(name + " - subtitles ready");
        } catch (Exception e) {
            System.err.println("Subtitle load failed: " + e);
            track.markUnavailable();
            status.accept(name + " - subtitles unavailable");
        }
    }

    
    /** Extracts the original media audio; MP3 (small), WAV as fallback. */
    private static Audio extractAudio(File mediaFile) throws Exception {
        try {
            return new Audio(extractAsMp3(mediaFile), "audio.mp3");
        } catch (Exception e) {
            System.err.println("MP3 extraction failed, trying WAV: " + e);
            return new Audio(extractAsWav(mediaFile), "audio.wav");
        }
    }

    private static byte[] extractAsMp3(File mediaFile) throws Exception {
    	FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile);
    	try {
    	    grabber.setVideoOption("threads", "1");
    	    grabber.start();
            if (!grabber.hasAudio()) {
                throw new IOException("Media has no audio track");
            }
            int sampleRate = grabber.getSampleRate();
            int channels = Math.max(1, grabber.getAudioChannels());

            ByteArrayOutputStream mp3 = new ByteArrayOutputStream();
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(mp3, 0, 0, channels);
            recorder.setFormat("mp3");
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
            recorder.setAudioBitrate(64_000);
            recorder.start();
            try {
            	Frame frame;
            	while ((frame = grabber.grabSamples()) != null) {
            	    if (hasSamples(frame)) {
            	        recorder.recordSamples(frame.samples);
            	    }
            	}
            } finally {
                recorder.stop();
                recorder.release();
            }
            byte[] bytes = mp3.toByteArray();
            if (bytes.length == 0) {
                throw new IOException("No audio samples extracted");
            }
            return bytes;
        } finally {
            closeQuietly(grabber);
        }
    }

    private static byte[] extractAsWav(File mediaFile) throws Exception {
    	FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile);
    	try {
    	    grabber.setVideoOption("threads", "1");
    	    grabber.start();
            if (!grabber.hasAudio()) {
                throw new IOException("Media has no audio track");
            }
            int sampleRate = grabber.getSampleRate();
            int channels = Math.max(1, grabber.getAudioChannels());
            AudioPlayer converter = new AudioPlayer();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (hasSamples(frame)) {
                    pcm.write(converter.copySamples(frame));
                }
            }
            return wavBytes(pcm.toByteArray(), sampleRate, channels);
        } finally {
            closeQuietly(grabber);
        }
    }

    private static boolean hasSamples(Frame frame) {
        return frame.samples != null
                && frame.samples.length > 0
                && frame.samples[0] != null
                && frame.samples[0].remaining() > 0;
    }

    private static byte[] wavBytes(byte[] pcm, int sampleRate, int channels) throws IOException {
        int byteRate = sampleRate * channels * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        DataOutputStream d = new DataOutputStream(out);
        d.writeBytes("RIFF");
        writeIntLE(d, 36 + pcm.length);
        d.writeBytes("WAVE");
        d.writeBytes("fmt ");
        writeIntLE(d, 16);
        writeShortLE(d, 1);
        writeShortLE(d, channels);
        writeIntLE(d, sampleRate);
        writeIntLE(d, byteRate);
        writeShortLE(d, channels * 2);
        writeShortLE(d, 16);
        d.writeBytes("data");
        writeIntLE(d, pcm.length);
        d.write(pcm);
        return out.toByteArray();
    }

    private static void writeIntLE(DataOutputStream d, int v) throws IOException {
        d.writeInt(Integer.reverseBytes(v));
    }

    private static void writeShortLE(DataOutputStream d, int v) throws IOException {
        d.writeShort(Integer.reverseBytes(v) >> 16);
    }

    
    private static String post(Audio audio) throws Exception {
        String boundary = "----COMP3011Boundary" + UUID.randomUUID();

        String contentType = audio.filename().endsWith(".mp3")
                ? "audio/mpeg"
                : "audio/wav";

        byte[] body = multipartBody(
                audio.data(),
                audio.filename(),
                contentType,
                boundary
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                throw new IOException("Subtitle server returned " + response.statusCode());
            } catch (IOException e) {
                if (attempts >= 3) {
                    throw e;
                }
                Thread.sleep(1500L * attempts);
            }
        }
    }

    static byte[] multipartBody(
            byte[] audio,
            String filename,
            String contentType,
            String boundary
    ) throws IOException {

        String crlf = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(("--" + boundary + crlf)
                .getBytes(StandardCharsets.UTF_8));

        out.write(("Content-Disposition: form-data; name=\"audio\"; filename=\""
                + filename + "\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));

        out.write(("Content-Type: " + contentType + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));

        out.write(audio);

        out.write((crlf + "--" + boundary + "--" + crlf)
                .getBytes(StandardCharsets.UTF_8));

        return out.toByteArray();
    }

    
    static List<SubtitleTrack.Cue> parseCues(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        List<double[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            double start = s.path("start").asDouble(0.0);
            double end = s.path("end").asDouble(0.0);
            String text = s.path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            times.add(new double[] { start, end });
            texts.add(text);
        }

        List<SubtitleTrack.Cue> cues = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            long startUs = Math.round(times.get(i)[0] * 1_000_000);
            long endUs = Math.round(times.get(i)[1] * 1_000_000);
            endUs = Math.min(endUs, startUs + MAX_DISPLAY_US);
            if (endUs < startUs + MIN_DISPLAY_US) {
                endUs = startUs + MIN_DISPLAY_US;
            }
            if (i + 1 < times.size()) {
                long nextStartUs = Math.round(times.get(i + 1)[0] * 1_000_000);
                if (nextStartUs > startUs
                        && nextStartUs - startUs >= MIN_DISPLAY_US
                        && endUs > nextStartUs) {
                    endUs = nextStartUs;
                }
            }
            cues.add(new SubtitleTrack.Cue(startUs, endUs,
                    SubtitleFormatter.wrap(texts.get(i))));
        }
        return cues;
    }


    private static Path cacheDir() {
        return Path.of(System.getProperty("user.home"), ".comp3011", "subtitles");
    }

    private static String cacheKey(File mediaFile) throws Exception {
        String seed = mediaFile.getName() + "|" + mediaFile.length()
                + "|" + mediaFile.lastModified();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(
                digest.digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static String shortName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void closeQuietly(FFmpegFrameGrabber grabber) {
        try {
            grabber.stop();
        } catch (Exception ignored) {
        }
        try {
            grabber.close();
        } catch (Exception ignored) {
        }
    }
}