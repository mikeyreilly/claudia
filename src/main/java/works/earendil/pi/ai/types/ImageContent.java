package works.earendil.pi.ai.types;

/** Image content block: base64 data plus mime type (e.g. "image/png"). */
public record ImageContent(String data, String mimeType) implements UserContent {
	public ImageContent {
		if (data == null || mimeType == null) {
			throw new IllegalArgumentException("data and mimeType must not be null");
		}
	}
}
