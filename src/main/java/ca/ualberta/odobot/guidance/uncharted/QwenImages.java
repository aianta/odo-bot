package ca.ualberta.odobot.guidance.uncharted;

import ca.ualberta.odobot.guidance.uncharted.QwenAgentConfig.CoordinateType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Screenshot preprocessing and coordinate mapping, ported from {@code mm_agents/qwen/images.py} and
 * {@code smart_resize} in {@code mm_agents/utils/qwen_vl_utils.py}.
 */
public final class QwenImages {

    static final int FACTOR = 32;
    static final int MIN_PIXELS = 56 * 56;
    static final int MAX_PIXELS = 16 * 16 * 4 * 12800;
    static final int MAX_LONG_SIDE = 8192;

    /**
     * The screenshot as sent to the model.
     */
    public record ProcessedImage(String base64Png, int width, int height) {
    }

    /**
     * A point in the original screenshot.
     */
    public record Point(int x, int y) {
    }

    private QwenImages() {
    }

    /**
     * Resize so both sides are multiples of {@link #FACTOR} and the pixel count stays within bounds, then encode
     * as base64 PNG.
     */
    public static ProcessedImage process(BufferedImage image) {
        int[] size = smartResize(image.getHeight(), image.getWidth(), FACTOR, MIN_PIXELS, MAX_PIXELS, MAX_LONG_SIDE);
        int height = size[0];
        int width = size[1];
        BufferedImage resized = resize(image, width, height);
        return new ProcessedImage(Base64.getEncoder().encodeToString(toPng(resized)), width, height);
    }

    /**
     * @return {height, width}
     */
    public static int[] smartResize(int height, int width, int factor, int minPixels, int maxPixels, int maxLongSide) {
        if (height < 2 || width < 2) {
            throw new IllegalArgumentException("height:%d or width:%d must be larger than factor:%d".formatted(height, width, factor));
        }
        if ((double) Math.max(height, width) / Math.min(height, width) > 200) {
            throw new IllegalArgumentException("absolute aspect ratio must be smaller than 100, got %d / %d".formatted(height, width));
        }

        if (Math.max(height, width) > maxLongSide) {
            double beta = (double) Math.max(height, width) / maxLongSide;
            height = (int) (height / beta);
            width = (int) (width / beta);
        }

        int hBar = roundByFactor(height, factor);
        int wBar = roundByFactor(width, factor);
        if ((long) hBar * wBar > maxPixels) {
            double beta = Math.sqrt((double) height * width / maxPixels);
            hBar = floorByFactor(height / beta, factor);
            wBar = floorByFactor(width / beta, factor);
        } else if ((long) hBar * wBar < minPixels) {
            double beta = Math.sqrt((double) minPixels / ((double) height * width));
            hBar = ceilByFactor(height * beta, factor);
            wBar = ceilByFactor(width * beta, factor);
        }
        return new int[]{hBar, wBar};
    }

    /**
     * Map a model coordinate back to the original screenshot. RELATIVE coordinates are on a 0-999 grid; ABSOLUTE
     * coordinates are in resized-screenshot pixels. Results are truncated like Python's {@code int()}.
     */
    public static Point adjustCoordinates(double x, double y, CoordinateType coordinateType,
                                          int originalWidth, int originalHeight,
                                          int processedWidth, int processedHeight) {
        if (originalWidth == 0 || originalHeight == 0) {
            return new Point((int) x, (int) y);
        }
        if (coordinateType == CoordinateType.ABSOLUTE) {
            if (processedWidth != 0 && processedHeight != 0) {
                double xScale = (double) originalWidth / processedWidth;
                double yScale = (double) originalHeight / processedHeight;
                return new Point((int) (x * xScale), (int) (y * yScale));
            }
            return new Point((int) x, (int) y);
        }

        // Python divides by 999, not 1000.
        double xScale = originalWidth / 999.0;
        double yScale = originalHeight / 999.0;
        return new Point((int) (x * xScale), (int) (y * yScale));
    }

    /**
     * Python's round() rounds half to even, as does Math.rint.
     */
    static int roundByFactor(double number, int factor) {
        return (int) Math.rint(number / factor) * factor;
    }

    static int ceilByFactor(double number, int factor) {
        return (int) Math.ceil(number / factor) * factor;
    }

    static int floorByFactor(double number, int factor) {
        return (int) Math.floor(number / factor) * factor;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resized = new BufferedImage(width, height, type);
        Graphics2D g = resized.createGraphics();
        try {
            // Pillow's Image.resize defaults to bicubic resampling.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
