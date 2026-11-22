package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Frame processor (-S / --subtitle-frames) that overlays the current
 * subtitle cue onto each frame:
 *
 *   - white sans-serif bold text with a black outline (legible on any bg),
 *   - drawn bottom-centre so the scene is disrupted as little as possible,
 *   - only touches pixels while a cue is active and subtitles are toggled on,
 *   - never throws: an exception here would otherwise kill an effect-stage
 *     thread and freeze the whole pipeline (see EffectPipeline.runStage).
 *
 * Timing uses info.timestampUs() (media time), so subtitles stay correct
 * across pause, seek forward/back and start-over without extra work.
 */
public class FrameSubtitler extends FrameProcessor {

    private final SubtitleTrack track = SubtitleTrack.get();

    private BufferedImage image;
    private byte[] data;
    private Graphics2D graphics;
    
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    
    private Font font;
    private FontMetrics fontMetrics;
    private BasicStroke outlineStroke;

    private List<String> cachedLines;
    private List<Shape> cachedShapes;
    
    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (!track.isVisible() || track.status() != SubtitleTrack.Status.READY) {
            return;
        }
        SubtitleTrack.Cue cue = track.cueAt(info.timestampUs());
        if (cue == null) {
            return;
        }
        try {
            drawCue(frame, cue);
        } catch (Exception e) {
            System.err.println("Subtitle render skipped: " + e);
        }
    }
    
    private void ensureImage(int width, int height) {
        if (image != null
                && cachedWidth == width
                && cachedHeight == height) {
            return;
        }

        if (graphics != null) {
            graphics.dispose();
        }

        image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_3BYTE_BGR
        );

        DataBufferByte buffer =
                (DataBufferByte) image.getRaster().getDataBuffer();

        data = buffer.getData();

        graphics = image.createGraphics();

        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );

        cachedWidth = width;
        cachedHeight = height;

        int fontSize = Math.max(14, height / 24);

        font = new Font(
                Font.SANS_SERIF,
                Font.BOLD,
                fontSize
        );

        graphics.setFont(font);

        fontMetrics = graphics.getFontMetrics(font);

        outlineStroke = new BasicStroke(
                Math.max(2f, fontSize / 7f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
        );

        cachedLines = null;
        cachedShapes = null;
    }
    
    private void cacheShapes(SubtitleTrack.Cue cue, int width, int height) {

        List<String> lines = List.copyOf(cue.lines());
        if (cachedShapes != null && lines.equals(cachedLines)) {
            return;
        }
        List<Shape> shapes = new ArrayList<>();
        int lineHeight = fontMetrics.getHeight();
        int blockHeight = lineHeight * lines.size();
        int margin = Math.max(8, height / 18);
        FontRenderContext frc = graphics.getFontRenderContext();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int x = (width - fontMetrics.stringWidth(line)) / 2;
            int baseline = height - margin - blockHeight
                    + i * lineHeight
                    + fontMetrics.getAscent();
            GlyphVector glyphs = font.createGlyphVector(frc, line);
            Shape outline = glyphs.getOutline(
                    (float) x,
                    (float) baseline
            );
            shapes.add(outline);
        }
        cachedLines = lines;
        cachedShapes = shapes;
    }

    private void drawCue(Frame frame, SubtitleTrack.Cue cue) {
        if (frame.image == null || frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer pixels)) {
            return;
        }
        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE
                && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            return;
        }
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        if (width <= 0 || height <= 0 || channels < 3) {
            return;
        }
        ensureImage(width, height);
        cacheShapes(cue, width, height);
        copyPixelsIn(
                pixels,
                data,
                width,
                height,
                channels,
                stride
        );
        graphics.setStroke(outlineStroke);
        graphics.setColor(Color.BLACK);
        for (Shape shape : cachedShapes) {
            graphics.draw(shape);
        }
        graphics.setColor(Color.WHITE);
        for (Shape shape : cachedShapes) {
            graphics.fill(shape);
        }
        copyPixelsOut(
                pixels,
                data,
                width,
                height,
                channels,
                stride
        );
    }

    private void copyPixelsIn(ByteBuffer pixels, byte[] data,
            int width, int height, int channels, int stride) {
        if (channels == 3) {
            int rowBytes = width * 3;
            int saved = pixels.position();
            for (int y = 0; y < height; y++) {
                pixels.position(y * stride);
                pixels.get(data, y * rowBytes, rowBytes);
            }
            pixels.position(saved);
            return;
        }
        int rowBytes = width * 3;
        for (int y = 0; y < height; y++) {
            int src = y * stride;
            int dst = y * rowBytes;
            for (int x = 0; x < width; x++) {
                data[dst] = pixels.get(src);
                data[dst + 1] = pixels.get(src + 1);
                data[dst + 2] = pixels.get(src + 2);
                src += channels;
                dst += 3;
            }
        }
    }

    private void copyPixelsOut(ByteBuffer pixels, byte[] data,
            int width, int height, int channels, int stride) {
        if (channels == 3) {
            int rowBytes = width * 3;
            int saved = pixels.position();
            for (int y = 0; y < height; y++) {
                pixels.position(y * stride);
                pixels.put(data, y * rowBytes, rowBytes);
            }
            pixels.position(saved);
            return;
        }
        int rowBytes = width * 3;
        for (int y = 0; y < height; y++) {
            int src = y * rowBytes;
            int dst = y * stride;
            for (int x = 0; x < width; x++) {
                pixels.put(dst, data[src]);
                pixels.put(dst + 1, data[src + 1]);
                pixels.put(dst + 2, data[src + 2]);
                src += 3;
                dst += channels;
            }
        }
    }
}