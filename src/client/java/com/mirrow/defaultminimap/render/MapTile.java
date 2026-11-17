package com.mirrow.defaultminimap.render;

import net.minecraft.client.renderer.texture.DynamicTexture;

/** One 128x128 pixel map texture at a given scale, like a vanilla map item. */
final class MapTile {
	final int scaleShift;
	final int tileX;
	final int tileZ;
	DynamicTexture texture;
	/** True when nothing in the tile has been explored: drawn as a plain black rectangle. */
	boolean empty;
	boolean built;
	/** Dirty rectangle in tile pixels, [x0, x1) x [y0, y1). Empty when x0 >= x1. */
	int dirtyX0;
	int dirtyY0;
	int dirtyX1;
	int dirtyY1;
	long lastUsedFrame;

	MapTile(final int scaleShift, final int tileX, final int tileZ) {
		this.scaleShift = scaleShift;
		this.tileX = tileX;
		this.tileZ = tileZ;
	}

	boolean isDirty() {
		return !this.built || this.dirtyX0 < this.dirtyX1;
	}

	void markDirty(final int x0, final int y0, final int x1, final int y1) {
		if (!this.built) {
			return;
		}

		if (this.dirtyX0 >= this.dirtyX1) {
			this.dirtyX0 = x0;
			this.dirtyY0 = y0;
			this.dirtyX1 = x1;
			this.dirtyY1 = y1;
		} else {
			this.dirtyX0 = Math.min(this.dirtyX0, x0);
			this.dirtyY0 = Math.min(this.dirtyY0, y0);
			this.dirtyX1 = Math.max(this.dirtyX1, x1);
			this.dirtyY1 = Math.max(this.dirtyY1, y1);
		}
	}

	void clearDirty() {
		this.dirtyX0 = this.dirtyX1 = this.dirtyY0 = this.dirtyY1 = 0;
	}

	void close() {
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
	}
}
