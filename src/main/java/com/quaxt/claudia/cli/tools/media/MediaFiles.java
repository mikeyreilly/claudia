package com.quaxt.claudia.cli.tools.media;

import com.quaxt.claudia.ai.types.ImageContent;
import com.quaxt.claudia.ai.types.TextContent;
import com.quaxt.claudia.ai.types.UserContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Image and PDF support for the read tool: file-type detection, page ranges and result formatting.
 * <p>
 * Decoding is delegated to a {@link MediaReader} loaded by reflection, so this class has no AWT or
 * PDFBox dependency and a native image (which cannot reach the reader) still works for text files.
 */
public final class MediaFiles {
    private MediaFiles() {}

    /** Largest image or PDF the read tool accepts. */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    /** Most PDF pages rendered by one read call. */
    public static final int MAX_RENDERED_PAGES = 10;
    /** Most characters of a page's text returned alongside its rendered image. */
    static final int MAX_RENDERED_PAGE_TEXT = 10_000;

    static final String NATIVE_UNAVAILABLE =
            "Reading images and PDFs is not available in the native build of Claudia; use the JVM build (claudia.jar).";

    /** File types the read tool returns as media rather than text. */
    public enum Kind {
        PNG("image/png", "PNG"),
        JPEG("image/jpeg", "JPEG"),
        GIF("image/gif", "GIF"),
        WEBP("image/webp", "WebP"),
        BMP("image/bmp", "BMP"),
        PDF("application/pdf", "PDF");

        public final String mimeType;
        public final String label;

        Kind(String mimeType, String label) {
            this.mimeType = mimeType;
            this.label = label;
        }

        public boolean isImage() {
            return this != PDF;
        }
    }

    /** Identifies an image or PDF from its leading bytes; returns null for anything else. */
    public static Kind sniff(Path file) throws IOException {
        byte[] head = new byte[16];
        int read;
        try (InputStream input = Files.newInputStream(file)) {
            read = input.readNBytes(head, 0, head.length);
        }
        long size = read < 6 ? read : Files.size(file);
        return sniff(head, read, size);
    }

    static Kind sniff(byte[] head, int length, long fileSize) {
        if (startsWith(head, length, 0x25, 0x50, 0x44, 0x46, 0x2D)) return Kind.PDF; // %PDF-
        if (startsWith(head, length, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return Kind.PNG;
        if (startsWith(head, length, 0xFF, 0xD8, 0xFF)) return Kind.JPEG;
        if (startsWith(head, length, 'G', 'I', 'F', '8')) return Kind.GIF;
        if (length >= 12 && startsWith(head, length, 'R', 'I', 'F', 'F')
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return Kind.WEBP;
        // "BM" alone is too weak (a text file may start with it); also require the header's file size to match.
        if (length >= 6 && head[0] == 'B' && head[1] == 'M') {
            long declared = (head[2] & 0xFFL) | (head[3] & 0xFFL) << 8 | (head[4] & 0xFFL) << 16 | (head[5] & 0xFFL) << 24;
            if (declared == fileSize) return Kind.BMP;
        }
        return null;
    }

    private static boolean startsWith(byte[] head, int length, int... prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((head[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    /** Reads an image: a one-line description followed by the image itself. */
    public static List<UserContent> readImage(Path file, Kind kind) throws IOException {
        requireReadableSize(file);
        byte[] bytes = Files.readAllBytes(file);
        MediaReader.Prepared prepared = reader().prepareImage(bytes, kind);
        String text = "Image: " + file.getFileName() + ", " + prepared.description();
        return List.of(new TextContent(text, null), image(prepared.image()));
    }

    /** Returns a PDF's text as lines: a summary line, then each page under a "--- Page n ---" heading. */
    public static List<String> pdfTextLines(Path file, Runnable checkpoint) throws IOException {
        requireReadableSize(file);
        try (MediaReader.PdfDocument pdf = reader().openPdf(file)) {
            int count = pdf.pageCount();
            List<String> lines = new ArrayList<>();
            lines.add("PDF: " + file.getFileName() + " — " + pages(count)
                    + ". To see pages as images, read it again with pages, e.g. pages=\"1\" or pages=\"2-3\" (at most "
                    + MAX_RENDERED_PAGES + " per call).");
            for (int page = 1; page <= count; page++) {
                checkpoint.run();
                lines.add("");
                lines.add("--- Page " + page + " ---");
                String text = pdf.pageText(page).strip();
                lines.addAll(text.isEmpty() ? List.of("(no text on this page)") : text.lines().toList());
            }
            return lines;
        }
    }

    /** Renders the requested PDF pages; each page contributes its text followed by its image. */
    public static List<UserContent> pdfPages(Path file, String pageSpec, Runnable checkpoint) throws IOException {
        requireReadableSize(file);
        try (MediaReader.PdfDocument pdf = reader().openPdf(file)) {
            int count = pdf.pageCount();
            List<Integer> pages = parsePages(pageSpec, count);
            List<UserContent> content = new ArrayList<>();
            content.add(new TextContent("PDF: " + file.getFileName() + " — " + pages(count) + "; showing "
                    + (pages.size() == 1 ? "page " : "pages ") + pageSpec.strip() + " as images.", null));
            for (int page : pages) {
                checkpoint.run();
                String text = pdf.pageText(page).strip();
                if (text.length() > MAX_RENDERED_PAGE_TEXT) {
                    text = text.substring(0, MAX_RENDERED_PAGE_TEXT)
                            + "\n[Page text truncated; read without pages to get all of it.]";
                }
                MediaReader.PreparedImage image = pdf.renderPage(page);
                content.add(new TextContent("--- Page " + page + " (" + image.width() + "×" + image.height() + " px) ---\n"
                        + (text.isEmpty() ? "(no text on this page)" : text), null));
                content.add(image(image));
            }
            return content;
        }
    }

    /**
     * Parses a page list such as {@code "2"}, {@code "1-3"} or {@code "1,3-4"} into sorted, distinct page numbers.
     */
    public static List<Integer> parsePages(String spec, int pageCount) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("pages must not be empty");
        TreeSet<Integer> pages = new TreeSet<>();
        for (String part : spec.split(",")) {
            String range = part.strip();
            int dash = range.indexOf('-');
            int first;
            int last;
            try {
                if (dash < 0) {
                    first = last = Integer.parseInt(range);
                } else {
                    first = Integer.parseInt(range.substring(0, dash).strip());
                    last = Integer.parseInt(range.substring(dash + 1).strip());
                }
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "Invalid pages \"" + spec + "\"; use a page number or range such as \"2\", \"1-3\" or \"1,4-5\"");
            }
            if (first < 1 || last < first) {
                throw new IllegalArgumentException("Invalid page range \"" + range + "\" in pages \"" + spec + "\"");
            }
            if (last > pageCount) {
                throw new IllegalArgumentException("Page " + last + " does not exist; the PDF has " + pages(pageCount));
            }
            for (int page = first; page <= last; page++) {
                pages.add(page);
                if (pages.size() > MAX_RENDERED_PAGES) {
                    throw new IllegalArgumentException(
                            "At most " + MAX_RENDERED_PAGES + " pages can be rendered per call; pages \"" + spec + "\" asks for more");
                }
            }
        }
        return List.copyOf(pages);
    }

    /** Human-readable size such as "812 KB". */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String pages(int count) {
        return count == 1 ? "1 page" : count + " pages";
    }

    private static ImageContent image(MediaReader.PreparedImage image) {
        return new ImageContent(Base64.getEncoder().encodeToString(image.data()), image.mimeType());
    }

    private static void requireReadableSize(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException(file.getFileName() + " is " + size(size) + "; images and PDFs larger than "
                    + size(MAX_FILE_BYTES) + " are not read");
        }
    }

    // ------------------------------------------------------------------ reader loading

    private static volatile MediaReader reader;

    /**
     * Loads the AWT/PDFBox-based reader. The class name is read through a system property so a native-image
     * build cannot resolve it statically, which keeps AWT and PDFBox out of the native executable.
     */
    static MediaReader reader() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            throw new UnsupportedOperationException(NATIVE_UNAVAILABLE);
        }
        MediaReader current = reader;
        if (current != null) return current;
        synchronized (MediaFiles.class) {
            if (reader == null) {
                String name = System.getProperty("claudia.mediaReader", MediaFiles.class.getPackageName() + ".DesktopMediaReader");
                try {
                    reader = (MediaReader) Class.forName(name).getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException | LinkageError error) {
                    throw new UnsupportedOperationException(
                            "Reading images and PDFs is not available: " + error, error);
                }
            }
            return reader;
        }
    }
}
