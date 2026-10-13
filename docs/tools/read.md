# `read`

Reads a text file, an image, a PDF, or a file inside a jar/zip archive.

Defined in [`LocalTools.read()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
image and PDF handling is in [`cli/tools/media`](../../src/main/java/com/quaxt/claudia/cli/tools/media).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | string | required | File to read. Relative paths resolve against the agent workspace; `~/` expands to the home directory. |
| `offset` | integer ≥ 1 | `1` | First line to return (1-based). Applies to text files and PDF text. |
| `limit` | integer ≥ 1 | unlimited | Maximum number of lines to return. Applies to text files and PDF text. |
| `pages` | string | none | PDF only: pages to return as images as well as text, e.g. `"1"`, `"2-4"` or `"1,3"`. At most 10 per call. |

## Behaviour

The file type is decided from the file's first bytes, not its extension.

### Text files

- The file is read as UTF-8, and lines `offset` … `offset + limit - 1` are
  returned.
- Output stops at 2,000 lines or 50 KB, whichever comes first. When there is
  more, the result ends with a `Use offset=N to continue.` hint.
- An `offset` past the end of the file is an error that gives the line count.
- Missing files, directories and unreadable files produce clear errors (`File
  not found`, `Not a regular file`, `Access denied`). A file that is neither
  UTF-8 text nor a supported image or PDF is reported as a binary file.

### Images

PNG, JPEG, GIF, WebP and BMP files are returned as an image the model can see,
preceded by a line such as `Image: shot.png, PNG, 3024×1964 px, 1.2 MB; resized
to 1330×864 px (PNG, 640 KB)`.

- Images are sent unchanged when they are at most 1568 px on the long edge, at
  most 1.15 megapixels and at most 3.75 MB. That keeps each image at about
  1,600 tokens and below every provider's size limit.
- Larger images are scaled down: PNG, GIF and BMP become PNG (or JPEG if the
  PNG is still too big), and JPEG stays JPEG.
- WebP cannot be decoded by the JDK, so it is passed through unchanged; WebP
  files over 3.75 MB are an error.
- Image and PDF files over 50 MB are not read.

### PDFs

- Without `pages`, the result is text: a summary line with the page count, then
  each page's text under a `--- Page n ---` heading. `offset`, `limit` and the
  2,000-line / 50 KB bound apply as for text files.
- With `pages`, each requested page contributes its heading and text (up to
  10,000 characters) followed by a rendered image of the page. Pages are
  rendered as large as the image limits allow; an A4 page comes out at about
  900×1275 px (roughly 110 DPI). `offset` and `limit` are ignored.
- Password-protected PDFs are an error.
- The first PDF render may take a few seconds while PDFBox indexes the system
  fonts; the index is cached in `~/.claudia/.pdfbox.cache`.

### Images and models

Images go to the model inside the tool result. Anthropic and OpenAI Responses
models receive them in the tool result itself, Gemini models as inline parts of
the same turn, and Chat Completions models in a user message that follows the
tool results. Models whose catalog entry does not list image input receive the
note `[image omitted: <model> does not accept image input]` instead. The
transcript shows each image as a line such as `[image/png image, 182 KB]`.

Image and PDF reading needs the JVM build. In the [native image](../build.md#native-image)
it fails with an error saying so; text files are unaffected.

### Archives

Add `!entry/path` after an archive path to read a file inside it, for example
`target/app.jar!META-INF/MANIFEST.MF`. Images and PDFs inside archives work too.

- `archive.jar!` or `archive.jar!some/dir/` lists the entries in that directory,
  with their sizes.
- Entry paths must not start with `/` and must stay inside the archive.
- If the entry doesn't exist, the error lists up to 20 nearby entries.
- Archives are read-only; [`write`](write.md) and [`edit`](edit.md) reject
  archive paths.

## UI summary

`Reading <path> (lines A-B)`, or `Reading <path> (pages P)` when `pages` is given.
