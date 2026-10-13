package com.quaxt.claudia;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.claudia.agent.AgentTool;
import com.quaxt.claudia.ai.types.ImageContent;
import com.quaxt.claudia.ai.types.TextContent;
import com.quaxt.claudia.ai.types.UserContent;
import com.quaxt.claudia.ai.util.AbortSignal;
import com.quaxt.claudia.cli.tools.media.MediaFiles;
import com.quaxt.claudia.cli.tools.media.MediaReader;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.claudia.ClaudiaOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;

/** The read tool's image and PDF support. */
class ReadMediaTest {
    @TempDir Path cwd;

    @BeforeAll
    static void loadTheReaderBeforeTheTestsUsePdfBox() throws Exception {
        // Its static initializer silences PDFBox logging and moves the font cache, as in production,
        // where nothing else touches PDFBox first.
        Class.forName("com.quaxt.claudia.cli.tools.media.DesktopMediaReader");
    }

    @Test
    void smallImagesAreReturnedUnchanged() throws Exception {
        Path png = writeImage("small.png", "png", 40, 30);
        AgentTool.ToolResult result = read(jsonObject().put("path", "small.png"));
        assertFalse(result.isError);
        assertEquals(2, result.content.size());
        assertTrue(text(result.content.get(0)).startsWith("Image: small.png, PNG, 40×30 px"), text(result.content.get(0)));
        ImageContent image = (ImageContent) result.content.get(1);
        assertEquals("image/png", image.mimeType);
        assertArrayEquals(Files.readAllBytes(png), Base64.getDecoder().decode(image.data));
    }

    @Test
    void largeImagesAreResizedWithinTheLimits() throws Exception {
        writeImage("large.png", "png", 4000, 3000);
        AgentTool.ToolResult result = read(jsonObject().put("path", "large.png"));
        assertTrue(text(result.content.get(0)).contains("4000×3000 px"), text(result.content.get(0)));
        assertTrue(text(result.content.get(0)).contains("resized to"), text(result.content.get(0)));
        ImageContent image = (ImageContent) result.content.get(1);
        assertEquals("image/png", image.mimeType);
        BufferedImage decoded = decode(image);
        assertWithinLimits(decoded);
        assertEquals(4000.0 / 3000.0, (double) decoded.getWidth() / decoded.getHeight(), 0.01);

        writeImage("photo.jpg", "jpeg", 3000, 2000);
        ImageContent jpeg = (ImageContent) read(jsonObject().put("path", "photo.jpg")).content.get(1);
        assertEquals("image/jpeg", jpeg.mimeType);
        assertWithinLimits(decode(jpeg));
    }

    @Test
    void bmpImagesAreConvertedToPng() throws Exception {
        writeImage("picture.bmp", "bmp", 20, 10);
        AgentTool.ToolResult result = read(jsonObject().put("path", "picture.bmp"));
        assertTrue(text(result.content.get(0)).contains("converted to PNG"), text(result.content.get(0)));
        assertEquals("image/png", ((ImageContent) result.content.get(1)).mimeType);
        assertEquals(20, decode((ImageContent) result.content.get(1)).getWidth());
    }

    @Test
    void textFilesThatLookLikeMediaStayText() throws Exception {
        Files.writeString(cwd.resolve("notes.txt"), "BMW notes\nsecond line\n");
        assertEquals("BMW notes\nsecond line", text(read(jsonObject().put("path", "notes.txt")).content.getFirst()));
        Files.write(cwd.resolve("blob.bin"), new byte[] {0x00, (byte) 0xC3, 0x28, (byte) 0xFE});
        IOException error = assertThrows(IOException.class, () -> read(jsonObject().put("path", "blob.bin")));
        assertTrue(error.getMessage().contains("binary file"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> read(jsonObject().put("path", "notes.txt").put("pages", "1")));
    }

    @Test
    void pdfsAreReadAsTextPerPage() throws Exception {
        writePdf(cwd.resolve("doc.pdf"), 3);
        String text = text(read(jsonObject().put("path", "doc.pdf")).content.getFirst());
        assertTrue(text.startsWith("PDF: doc.pdf — 3 pages."), text);
        assertTrue(text.contains("--- Page 2 ---\nHello from page 2"), text);
        assertTrue(text.contains("--- Page 3 ---\nHello from page 3"), text);

        String slice = text(read(jsonObject().put("path", "doc.pdf").put("offset", 3).put("limit", 2)).content.getFirst());
        assertTrue(slice.startsWith("--- Page 1 ---\nHello from page 1"), slice);
        assertTrue(slice.contains("Use offset=5 to continue."), slice);
    }

    @Test
    void pdfPagesAreRenderedAsImages() throws Exception {
        writePdf(cwd.resolve("doc.pdf"), 3);
        AgentTool.ToolResult result = read(jsonObject().put("path", "doc.pdf").put("pages", "2-3"));
        assertEquals(5, result.content.size());
        assertEquals("PDF: doc.pdf — 3 pages; showing pages 2-3 as images.", text(result.content.get(0)));
        assertTrue(text(result.content.get(1)).startsWith("--- Page 2 ("), text(result.content.get(1)));
        assertTrue(text(result.content.get(1)).endsWith("---\nHello from page 2"), text(result.content.get(1)));
        BufferedImage page = decode((ImageContent) result.content.get(2));
        assertWithinLimits(page);
        // A4 is limited by the pixel budget: about 900×1275 px, i.e. roughly 110 DPI.
        assertTrue(page.getWidth() > 850 && page.getHeight() > 1200, page.getWidth() + "×" + page.getHeight());
        assertTrue(text(result.content.get(3)).contains("Hello from page 3"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> read(jsonObject().put("path", "doc.pdf").put("pages", "4")));
        assertTrue(missing.getMessage().contains("the PDF has 3 pages"), missing.getMessage());
    }

    @Test
    void pageListsAreParsedAndBounded() {
        assertEquals(List.of(1, 2, 3), MediaFiles.parsePages("3, 1-2,2", 5));
        assertEquals(List.of(4), MediaFiles.parsePages("4", 4));
        for (String invalid : List.of("", "a", "0", "3-2", "1-", "5")) {
            assertThrows(IllegalArgumentException.class, () -> MediaFiles.parsePages(invalid, 4), invalid);
        }
        assertEquals(10, MediaFiles.parsePages("1-10", 20).size());
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class, () -> MediaFiles.parsePages("1-11", 20));
        assertTrue(tooMany.getMessage().contains("At most 10 pages"), tooMany.getMessage());
    }

    @Test
    void mediaInsideArchivesCanBeRead() throws Exception {
        Path pdf = cwd.resolve("inner.pdf");
        writePdf(pdf, 1);
        Path png = writeImage("inner.png", "png", 10, 10);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cwd.resolve("bundle.zip")))) {
            for (Path entry : List.of(pdf, png)) {
                zip.putNextEntry(new ZipEntry("docs/" + entry.getFileName()));
                Files.copy(entry, zip);
                zip.closeEntry();
            }
        }
        assertTrue(text(read(jsonObject().put("path", "bundle.zip!docs/inner.pdf")).content.getFirst())
                .contains("Hello from page 1"));
        assertEquals(3, read(jsonObject().put("path", "bundle.zip!docs/inner.pdf").put("pages", "1")).content.size());
        assertInstanceOf(ImageContent.class, read(jsonObject().put("path", "bundle.zip!docs/inner.png")).content.get(1));
    }

    @Test
    void nativeImagesReportThatMediaIsUnavailable() throws Exception {
        writeImage("small.png", "png", 4, 4);
        Files.writeString(cwd.resolve("notes.txt"), "still text\n");
        System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
        try {
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                    () -> read(jsonObject().put("path", "small.png")));
            assertTrue(error.getMessage().contains("native build"), error.getMessage());
            assertEquals("still text", text(read(jsonObject().put("path", "notes.txt")).content.getFirst()));
        } finally {
            System.clearProperty("org.graalvm.nativeimage.imagecode");
        }
    }

    // ------------------------------------------------------------------ helpers

    private AgentTool.ToolResult read(ObjectNode arguments) throws Exception {
        try (var runtime = new ClaudiaOperations()) {
            AgentTool tool = runtime.builtInTools(cwd, ignored -> {}).stream()
                    .filter(candidate -> ClaudiaOperations.toolName(candidate).equals("read"))
                    .findFirst()
                    .orElseThrow();
            return runtime.executeTool(tool, "test", arguments, new AbortSignal(), ignored -> {});
        }
    }

    private static String text(UserContent content) {
        return ((TextContent) content).text;
    }

    private static BufferedImage decode(ImageContent image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.data)));
    }

    private static void assertWithinLimits(BufferedImage image) {
        assertTrue(Math.max(image.getWidth(), image.getHeight()) <= MediaReader.MAX_EDGE, image.getWidth() + "×" + image.getHeight());
        assertTrue((long) image.getWidth() * image.getHeight() <= MediaReader.MAX_PIXELS, image.getWidth() + "×" + image.getHeight());
    }

    private Path writeImage(String name, String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(0x08787d));
            graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally {
            graphics.dispose();
        }
        Path file = cwd.resolve(name);
        try (OutputStream output = Files.newOutputStream(file)) {
            assertTrue(ImageIO.write(image, format, output));
        }
        return file;
    }

    private static void writePdf(Path file, int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int page = 1; page <= pages; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    content.newLineAtOffset(72, 760);
                    content.showText("Hello from page " + page);
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
    }
}
