package com.quaxt.codingagent.ai.types;

import java.util.Objects;

/** Image content block: base64 data plus mime type (e.g. "image/png"). */
public final class ImageContent implements UserContent {
	public String data;
	public String mimeType;

	public ImageContent(String data, String mimeType) {
		this.data = data;
		this.mimeType = mimeType;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ImageContent that
				&& Objects.equals(data, that.data)
				&& Objects.equals(mimeType, that.mimeType);
	}

	@Override
	public int hashCode() {
		return Objects.hash(data, mimeType);
	}

	@Override
	public String toString() {
		return "ImageContent[data=" + data + ", mimeType=" + mimeType + "]";
	}
}
