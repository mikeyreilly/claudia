package com.quaxt.claudia.cli.tools.media;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Decodes images and PDFs for the read tool.
 * <p>
 * The implementation ({@link DesktopMediaReader}) needs java.desktop and PDFBox, so callers obtain it only
 * through {@link MediaFiles}, which loads it reflectively and never in a native image.
 */
public interface MediaReader {

    /** Largest edge, in pixels, of an image sent to a model. */
    int MAX_EDGE = 1568;
    /** Largest pixel count of an image sent to a model (about 1,600 tokens). */
    long MAX_PIXELS = 1_150_000;
    /** Largest encoded size of an image sent to a model; its base64 form stays below 5 MB. */
    int MAX_BYTES = 3_750_000;

    /**
     * An image ready to send to a model.
     *
     * @param width  pixel width, or -1 when the format could not be decoded (WebP)
     * @param height pixel height, or -1 when unknown
     */
    record PreparedImage(String mimeType, byte[] data, int width, int height) {}

    /**
     * Returns the image unchanged when it is within the limits, otherwise a scaled and re-encoded copy.
     *
     * @return the prepared image and a short human-readable description of what was done
     */
    Prepared prepareImage(byte[] bytes, MediaFiles.Kind kind) throws IOException;

    /** Result of {@link #prepareImage}: the image plus a description such as "PNG, 3024×1964 px; resized to …". */
    record Prepared(PreparedImage image, String description) {}

    /** Opens a PDF. The caller must close the returned document. */
    PdfDocument openPdf(Path file) throws IOException;

    /** An open PDF. Page numbers are 1-based. */
    interface PdfDocument extends AutoCloseable {
        int pageCount();

        String pageText(int page) throws IOException;

        PreparedImage renderPage(int page) throws IOException;

        @Override
        void close() throws IOException;
    }
}
