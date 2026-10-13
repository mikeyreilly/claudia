package com.quaxt.claudia.cli.tools.media;

import com.quaxt.claudia.ClaudiaPaths;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * {@link MediaReader} built on ImageIO and Apache PDFBox. Loaded reflectively by {@link MediaFiles}.
 */
public final class DesktopMediaReader implements MediaReader {

    /** Strong references: java.util.logging holds loggers weakly, so levels set on a dropped logger are lost. */
    private static final List<Logger> SILENCED_LOGGERS;
    private static final float JPEG_QUALITY = 0.85f;

    static {
        // Never initialise a windowing toolkit (on macOS that would add a Dock icon and could steal focus).
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
        // PDFBox logs through commons-logging to java.util.logging, whose console handler writes to stderr and
        // would corrupt the terminal UI.
        SILENCED_LOGGERS = List.of(Logger.getLogger("org.apache.pdfbox"), Logger.getLogger("org.apache.fontbox"));
        for (Logger logger : SILENCED_LOGGERS) logger.setLevel(Level.OFF);
        // Keep PDFBox's system font cache with Claudia's other files instead of ~/.pdfbox.cache.
        if (System.getProperty("pdfbox.fontcache") == null) {
            try {
                Path directory = ClaudiaPaths.forCurrentUser().settingsDirectory();
                Files.createDirectories(directory);
                System.setProperty("pdfbox.fontcache", directory.toString());
            } catch (IOException | RuntimeException ignored) {
                // PDFBox falls back to the home directory.
            }
        }
    }

    public DesktopMediaReader() {}

    // ------------------------------------------------------------------ images

    @Override
    public Prepared prepareImage(byte[] bytes, MediaFiles.Kind kind) throws IOException {
        String original = kind.label + ", ";
        if (kind == MediaFiles.Kind.WEBP) {
            // The JDK has no WebP decoder, so WebP can only be passed through.
            if (bytes.length > MAX_BYTES) {
                throw new IOException("WebP image is " + MediaFiles.size(bytes.length) + "; WebP images over "
                        + MediaFiles.size(MAX_BYTES) + " cannot be resized. Convert it to PNG or JPEG first.");
            }
            return new Prepared(new PreparedImage(kind.mimeType, bytes, -1, -1), original + MediaFiles.size(bytes.length));
        }
        int[] dimensions = dimensions(bytes);
        int width = dimensions[0];
        int height = dimensions[1];
        original += width + "×" + height + " px, " + MediaFiles.size(bytes.length);
        double scale = Math.min(1, scaleFor(width, height));
        boolean passThrough = kind != MediaFiles.Kind.BMP && scale >= 1 && bytes.length <= MAX_BYTES;
        if (passThrough) {
            return new Prepared(new PreparedImage(kind.mimeType, bytes, width, height), original);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("Unable to decode " + kind.label + " image");
        PreparedImage prepared = encodeWithinLimits(image, scale, kind == MediaFiles.Kind.JPEG);
        String format = prepared.mimeType().equals("image/png") ? "PNG" : "JPEG";
        String change = prepared.width() == width && prepared.height() == height
                ? "converted to " + format + ", " + MediaFiles.size(prepared.data().length)
                : "resized to " + prepared.width() + "×" + prepared.height() + " px (" + format + ", "
                        + MediaFiles.size(prepared.data().length) + ")";
        return new Prepared(prepared, original + "; " + change);
    }

    private static int[] dimensions(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    /** Largest scale factor at which an image fits both {@link #MAX_EDGE} and {@link #MAX_PIXELS}; may exceed 1. */
    static double scaleFor(double width, double height) {
        return Math.min(MAX_EDGE / Math.max(width, height), Math.sqrt(MAX_PIXELS / (width * height)));
    }

    /** Scales and encodes an image, shrinking further until it fits in {@link #MAX_BYTES}. */
    private static PreparedImage encodeWithinLimits(BufferedImage source, double scale, boolean preferJpeg) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++, scale *= 0.8) {
            int width = Math.max(1, (int) Math.floor(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.floor(source.getHeight() * scale));
            BufferedImage scaled = scale >= 1 ? source : resize(source, width, height);
            if (!preferJpeg) {
                byte[] png = encode(scaled, "png");
                if (png.length <= MAX_BYTES) return new PreparedImage("image/png", png, scaled.getWidth(), scaled.getHeight());
            }
            byte[] jpeg = encode(scaled, "jpeg");
            if (jpeg.length <= MAX_BYTES) return new PreparedImage("image/jpeg", jpeg, scaled.getWidth(), scaled.getHeight());
        }
        throw new IOException("Unable to shrink the image below " + MediaFiles.size(MAX_BYTES));
    }

    /** Downscales in halving steps first, which avoids the aliasing of a single large bilinear step. */
    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        do {
            w = Math.max(width, w / 2);
            h = Math.max(height, h / 2);
            boolean alpha = current.getColorModel().hasAlpha();
            BufferedImage next = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = next.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(current, 0, 0, w, h, null);
            } finally {
                graphics.dispose();
            }
            current = next;
        } while (w != width || h != height);
        return current;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (format.equals("png")) {
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("No PNG encoder available");
            return bytes.toByteArray();
        }
        // JPEG has no alpha channel: flatten onto white.
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG encoder available");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------ PDFs

    @Override
    public PdfDocument openPdf(Path file) throws IOException {
        PDDocument document;
        try {
            document = file.getFileSystem() == FileSystems.getDefault()
                    ? Loader.loadPDF(file.toFile())
                    : Loader.loadPDF(Files.readAllBytes(file));
        } catch (InvalidPasswordException error) {
            throw new IOException(file.getFileName() + " is password-protected and cannot be read");
        } catch (IOException error) {
            throw new IOException("Unable to read PDF " + file.getFileName() + ": " + error.getMessage(), error);
        }
        return new Pdf(document);
    }

    private static final class Pdf implements PdfDocument {
        private final PDDocument document;
        private final PDFTextStripper stripper = new PDFTextStripper();
        private PDFRenderer renderer;

        Pdf(PDDocument document) {
            this.document = document;
        }

        @Override
        public int pageCount() {
            return document.getNumberOfPages();
        }

        @Override
        public String pageText(int page) throws IOException {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }

        @Override
        public PreparedImage renderPage(int page) throws IOException {
            PDPage pdPage = document.getPage(page - 1);
            PDRectangle box = pdPage.getCropBox();
            double width = box.getWidth();
            double height = box.getHeight();
            if (pdPage.getRotation() % 180 != 0) {
                double swap = width;
                width = height;
                height = swap;
            }
            // One PDF unit is one pixel at scale 1 (72 DPI); pages are rendered as large as the limits allow.
            float scale = (float) (scaleFor(width, height) * 0.999); // margin for the renderer's rounding
            if (renderer == null) renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImage(page - 1, scale, ImageType.RGB);
            return encodeWithinLimits(image, 1, false);
        }

        @Override
        public void close() throws IOException {
            document.close();
        }
    }
}
