package comp3011.assignment1.service;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Calls OpenAI whisper-1 with verbose_json + word/segment timestamp
 * granularities to produce subtitle text and timing information.
 *
 * - API key: env OPENAI_API_KEY, else key.txt in working directory (never hardcoded).
 * - In-memory cache keyed by SHA-256 of the audio bytes: repeat plays of the
 *   same media skip the paid API call and return instantly (also keeps
 *   /usage accurate - only real API calls add usage).
 * - WAV excerpts of a cached file are matched by PCM byte region and their segment
 *   timestamps shifted, so re-playing part of the same media also skips the paid call.
 * - Segments with no_speech_prob > 0.6 AND avg_logprob < -1.0 are filtered
 *   out per the Assignment 3 specification.
 */
@Service
public class WhisperSubtitleService {

    private static final Logger log = LoggerFactory.getLogger(WhisperSubtitleService.class);
    private static final double NO_SPEECH_THRESHOLD = 0.6;
    private static final double AVG_LOG_PROB_THRESHOLD = -1.0;
    static record Entry(byte[] audio, String result) {
    }

    private final RestClient restClient;
    private final ServerStatsService statsService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final String configuredKey;

    public WhisperSubtitleService(
            RestClient.Builder restClientBuilder,
            @Value("${openai.api.key}") String apiKey,
            ServerStatsService statsService) {

    	JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
    	        HttpClient.newBuilder()
    	                .connectTimeout(Duration.ofSeconds(10))
    	                .build());
    	requestFactory.setReadTimeout(Duration.ofSeconds(180));

        this.restClient = restClientBuilder
                .baseUrl("https://api.openai.com")
                .requestFactory(requestFactory)
                .build();
        this.configuredKey = apiKey == null ? "" : apiKey.trim();
        this.statsService = statsService;
    }

    /**
     * Returns a JSON body:
     * { "duration": <sec>, "text": "...",
     *   "segments": [ {"start":..,"end":..,"text":".."}, ... ],
     *   "words":    [ {"word":"..","start":..,"end":..}, ... ] }
     * Segments failing the speech-probability filter are removed.
     */
    public String transcribeSubtitles(MultipartFile audio) throws Exception {
        byte[] bytes = audio.getBytes();
        String cacheKey = sha256Hex(bytes);

        Entry cached = cache.get(cacheKey);
        if (cached != null) {
            log.info("Subtitle exact cache hit ({} bytes) - skipping OpenAI",
                    bytes.length);
            return cached.result();
        }
        
        for (Entry entry : cache.values()) {
            int offset = wavRegionOffset(entry.audio(), bytes);
            if (offset >= 0) {
                log.info("Subtitle region cache hit ({} bytes at PCM offset {}) - skipping OpenAI",
                        bytes.length, offset);
                return shiftSegments(entry.result(), excerptShiftSeconds(entry.audio(), offset));
            }
        }

        String response = callWhisper(bytes, audio.getOriginalFilename());
        JsonNode root = mapper.readTree(response);

        double duration = root.path("duration").asDouble(0.0);
        statsService.addUsageSeconds(duration);   // only real API calls count

        String result = filterAndShape(root);
        cache.put(cacheKey, new Entry(bytes.clone(), result));
        log.info("Subtitle transcription ok: duration={}s segments={}",
                duration, root.path("segments").size());
        return result;
    }

    private String callWhisper(byte[] bytes, String filename) throws Exception {
        ByteArrayResource audioResource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "audio.mp3";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", audioResource);
        body.add("model", "whisper-1");
        body.add("response_format", "verbose_json");
        body.add("timestamp_granularities[]", "word");
        body.add("timestamp_granularities[]", "segment");

        return restClient.post()
                .uri("/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + resolveApiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /**
     * Filters segments and reshapes the raw whisper response for the player.
     * Kept package-private static so it can be unit tested without the network.
     *
     * OpenAI field names: no_speech_prob, avg_logprob (spec: noSpeechProb/aveLogProb).
     */
    static String filterAndShape(JsonNode root) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode out = mapper.createObjectNode();
        out.put("duration", root.path("duration").asDouble(0.0));
        out.put("text", root.path("text").asText(""));

        ArrayNode segments = out.putArray("segments");
        for (JsonNode s : root.path("segments")) {
            double noSpeechProb = s.path("no_speech_prob").asDouble(0.0);
            double avgLogProb = s.path("avg_logprob").asDouble(0.0);
            // Spec: not displayed when noSpeechProb > 0.6 AND aveLogProb < -1.0
            if (noSpeechProb > NO_SPEECH_THRESHOLD
                    && avgLogProb < AVG_LOG_PROB_THRESHOLD) {
                continue;
            }
            ObjectNode seg = segments.addObject();
            seg.put("start", s.path("start").asDouble(0.0));
            seg.put("end", s.path("end").asDouble(0.0));
            seg.put("text", s.path("text").asText(""));
            seg.put("noSpeechProb", noSpeechProb);
            seg.put("aveLogProb", avgLogProb);
        }

        ArrayNode words = out.putArray("words");
        for (JsonNode w : root.path("words")) {
            ObjectNode word = words.addObject();
            word.put("word", w.path("word").asText(""));
            word.put("start", w.path("start").asDouble(0.0));
            word.put("end", w.path("end").asDouble(0.0));
        }
        return mapper.writeValueAsString(out);
    }

    // Key: env/property first, then key.txt (used when the viva USB key is provided).
    private String resolveApiKey() {
        if (!configuredKey.isEmpty()) {
            return configuredKey;
        }
        try {
            Path keyFile = Path.of("key.txt");
            if (Files.isRegularFile(keyFile)) {
                String key = Files.readString(keyFile).trim();
                if (!key.isEmpty()) {
                    return key;
                }
            }
        } catch (Exception e) {
            log.error("Could not read key.txt: {}", e.getMessage());
        }
        throw new IllegalStateException(
                "No OpenAI API key: set OPENAI_API_KEY or place key.txt in the working directory");
    }
    
    static final int WAV_HEADER_BYTES = 44;
    /**
     * Offset of the requested WAV's audio payload inside the cached WAV's payload,
     * or -1. Returns WAV_HEADER_BYTES for an exact-prefix match.
     * shortcut: naive O(n*m) scan - fine for short excerpts, worst case slow on
     * silence-heavy audio; upgrade to a rolling hash if noticed.
     */
    static int wavRegionOffset(byte[] stored, byte[] requested) {
        if (requested.length <= WAV_HEADER_BYTES || stored.length < requested.length) {
            return -1;
        }
        if (!isWav(stored) || !isWav(requested)) {
            return -1;
        }
        int pcmLen = requested.length - WAV_HEADER_BYTES;
        outer:
        for (int i = WAV_HEADER_BYTES; i <= stored.length - pcmLen; i++) {
            if (stored[i] != requested[WAV_HEADER_BYTES]) {
                continue;
            }
            for (int j = 1; j < pcmLen; j++) {
                if (stored[i + j] != requested[WAV_HEADER_BYTES + j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static double excerptShiftSeconds(byte[] stored, int pcmOffset) {
        int byteRate = pcmOffset >= WAV_HEADER_BYTES && isWav(stored) ? readLeInt(stored, 28) : 0;
        return byteRate > 0 ? (pcmOffset - WAV_HEADER_BYTES) / (double) byteRate : 0.0;
    }

    static String shiftSegments(String result, double shiftSeconds) throws Exception {
        if (shiftSeconds <= 0) {
            return result;
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(result);
        ArrayNode segments = (ArrayNode) root.get("segments");
        ArrayNode kept = mapper.createArrayNode();
        if (segments != null) {
            for (JsonNode s : segments) {
                double end = s.path("end").asDouble() - shiftSeconds;
                if (end <= 0) {
                    continue;
                }
                ObjectNode seg = (ObjectNode) s;
                seg.put("start", Math.max(0.0, seg.path("start").asDouble() - shiftSeconds));
                seg.put("end", end);
                kept.add(seg);
            }
            root.set("segments", kept);
        }
        return mapper.writeValueAsString(root);
    }

    private static boolean isWav(byte[] b) {
        return b.length >= WAV_HEADER_BYTES
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
    }

    private static int readLeInt(byte[] b, int at) {
        return (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8)
                | ((b[at + 2] & 0xff) << 16) | ((b[at + 3] & 0xff) << 24);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}