package comp3011.assignment1.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.service.WhisperSubtitleService;

/**
 * Endpoint: POST /api/v1/subtitles
 * - Accepts multipart/form-data audio (parameter name: "audio") - the original
 *   media audio sent by the video player.
 * - Returns subtitle text + timing JSON from WhisperSubtitleService.
 * - 502 when the transcription upstream fails (player treats non-200 as
 *   "subtitles unavailable" and keeps playing).
 */
@RestController
@RequestMapping("/api/v1")
public class SubtitleController {
    private static final Logger log = LoggerFactory.getLogger(SubtitleController.class);
    private final WhisperSubtitleService subtitleService;

    public SubtitleController(WhisperSubtitleService subtitleService) {
        this.subtitleService = subtitleService;
    }

    @PostMapping("/subtitles")
    public ResponseEntity<String> subtitles(@RequestParam("audio") MultipartFile audio) {
        try {
            String result = subtitleService.transcribeSubtitles(audio);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (Exception e) {
            log.error("Subtitle transcription failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}