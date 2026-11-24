package io.github.hzllfeu.claudecraft.client;

/** Scale applied to scissor rectangles while the Claude screen renders with its own UI scale (see GuiGraphicsScissorMixin). */
public final class ScissorScale {
	public static double k = 1;

	private ScissorScale() {
	}

	public static int min(int v) {
		return k == 1 ? v : (int) Math.floor(v * k);
	}

	public static int max(int v) {
		return k == 1 ? v : (int) Math.ceil(v * k);
	}
}
