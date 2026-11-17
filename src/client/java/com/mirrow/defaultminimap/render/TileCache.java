package com.mirrow.defaultminimap.render;

import com.mirrow.defaultminimap.data.MapRegion;
import com.mirrow.defaultminimap.data.MapStore;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.material.MapColor;

/**
 * Keeps 128x128 map textures for every scale that is on screen. Textures are only written and
 * uploaded when the underlying chunk data changed; drawing just reuses them.
 */
public final class TileCache {
	public static final int TILE_SIZE = 128;
	/** Scales 1:1 to 1:16 blocks per pixel. */
	public static final int MAX_SCALE_SHIFT = 4;
	private static final int MAX_TILES = 160;
	private static final int UNEXPLORED = 0xFF000000;

	private final Long2ObjectOpenHashMap<MapTile> tiles = new Long2ObjectOpenHashMap<>();
	private final List<MapTile> evictScratch = new ArrayList<>();
	private MapStore store;
	private long frame;
	private int buildBudget;

	// Scratch buffers for majority-colour counting (vanilla picks the most common colour).
	private final int[] colorCounts = new int[64];
	private final int[] colorOrder = new int[16 * 16];

	public void setStore(final MapStore store) {
		if (this.store != store) {
			this.clear();
			this.store = store;
		}
	}

	public MapStore store() {
		return this.store;
	}

	public void clear() {
		for (MapTile tile : this.tiles.values()) {
			tile.close();
		}

		this.tiles.clear();
	}

	/** Called once at the start of every draw pass. */
	public void beginFrame(final int budget) {
		this.frame++;
		this.buildBudget = budget;
		if (this.tiles.size() > MAX_TILES) {
			this.evict();
		}
	}

	private static long key(final int scaleShift, final int tileX, final int tileZ) {
		return (long)scaleShift << 56 | ((long)tileX & 0xFFFFFFFL) << 28 | (long)tileZ & 0xFFFFFFFL;
	}

	private void evict() {
		List<MapTile> candidates = this.evictScratch;
		candidates.clear();
		for (MapTile tile : this.tiles.values()) {
			// Never free a texture that might still be queued for drawing.
			if (tile.lastUsedFrame < this.frame - 2) {
				candidates.add(tile);
			}
		}

		candidates.sort((a, b) -> Long.compare(a.lastUsedFrame, b.lastUsedFrame));
		int toRemove = this.tiles.size() - MAX_TILES * 3 / 4;
		for (int i = 0; i < candidates.size() && i < toRemove; i++) {
			MapTile tile = candidates.get(i);
			tile.close();
			this.tiles.remove(key(tile.scaleShift, tile.tileX, tile.tileZ));
		}

		candidates.clear();
	}

	/**
	 * Returns the tile, building or refreshing it if there is budget left this frame.
	 * The returned tile may not be built yet (then it has no texture and is not empty).
	 */
	MapTile get(final int scaleShift, final int tileX, final int tileZ) {
		long key = key(scaleShift, tileX, tileZ);
		MapTile tile = this.tiles.get(key);
		if (tile == null) {
			tile = new MapTile(scaleShift, tileX, tileZ);
			this.tiles.put(key, tile);
		}

		tile.lastUsedFrame = this.frame;
		if (tile.isDirty() && this.buildBudget > 0 && this.store != null) {
			this.buildBudget--;
			this.build(tile);
		}

		return tile;
	}

	/** A chunk got new data: mark the covered pixels of every cached tile as dirty. */
	public void markChunkDirty(final int chunkX, final int chunkZ) {
		if (this.tiles.isEmpty()) {
			return;
		}

		int minX = chunkX << 4;
		int minZ = chunkZ << 4;
		for (int shift = 0; shift <= MAX_SCALE_SHIFT; shift++) {
			int px0 = minX >> shift;
			int pz0 = minZ >> shift;
			int px1 = (minX + 15 >> shift) + 1;
			// One extra row to the south: its shading compares against this chunk's heights.
			int pz1 = (minZ + 15 >> shift) + 2;
			this.markPixelsDirty(shift, px0, pz0, px1, pz1);
		}
	}

	private void markPixelsDirty(final int shift, final int px0, final int pz0, final int px1, final int pz1) {
		int tx0 = Math.floorDiv(px0, TILE_SIZE);
		int tx1 = Math.floorDiv(px1 - 1, TILE_SIZE);
		int tz0 = Math.floorDiv(pz0, TILE_SIZE);
		int tz1 = Math.floorDiv(pz1 - 1, TILE_SIZE);
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				MapTile tile = this.tiles.get(key(shift, tx, tz));
				if (tile == null) {
					continue;
				}

				if (tile.empty) {
					tile.built = false;
					tile.empty = false;
					continue;
				}

				int baseX = tx * TILE_SIZE;
				int baseZ = tz * TILE_SIZE;
				tile.markDirty(
					Math.max(0, px0 - baseX), Math.max(0, pz0 - baseZ), Math.min(TILE_SIZE, px1 - baseX), Math.min(TILE_SIZE, pz1 - baseZ)
				);
			}
		}
	}

	private boolean tileHasData(final MapTile tile) {
		int span = TILE_SIZE << tile.scaleShift;
		int minX = tile.tileX * span;
		int minZ = tile.tileZ * span;
		int rx0 = minX >> MapRegion.SHIFT;
		int rz0 = minZ >> MapRegion.SHIFT;
		int rx1 = minX + span - 1 >> MapRegion.SHIFT;
		int rz1 = minZ + span - 1 >> MapRegion.SHIFT;
		for (int rz = rz0; rz <= rz1; rz++) {
			for (int rx = rx0; rx <= rx1; rx++) {
				MapRegion region = this.store.region(rx, rz, false);
				if (region != null && region.hasAnyExplored()) {
					return true;
				}
			}
		}

		return false;
	}

	private void build(final MapTile tile) {
		int x0;
		int y0;
		int x1;
		int y1;
		if (!tile.built) {
			tile.built = true;
			if (!this.tileHasData(tile)) {
				tile.empty = true;
				tile.close();
				tile.clearDirty();
				return;
			}

			tile.empty = false;
			x0 = 0;
			y0 = 0;
			x1 = TILE_SIZE;
			y1 = TILE_SIZE;
		} else {
			x0 = tile.dirtyX0;
			y0 = tile.dirtyY0;
			x1 = tile.dirtyX1;
			y1 = tile.dirtyY1;
		}

		tile.clearDirty();
		if (tile.texture == null) {
			tile.texture = new DynamicTexture(() -> "mirrows_default_minimap map tile", TILE_SIZE, TILE_SIZE, true);
			x0 = 0;
			y0 = 0;
			x1 = TILE_SIZE;
			y1 = TILE_SIZE;
		}

		this.fill(tile, x0, y0, x1, y1);
		tile.texture.upload();
	}

	// Results of sampling one map pixel.
	private boolean sampleExplored;
	private int sampleColor;
	private double sampleHeight;
	private int sampleDepth;

	/**
	 * Samples one map pixel the way vanilla does: majority colour over the scale x scale block area,
	 * average surface height and average water depth.
	 */
	private void sample(final int blockX, final int blockZ, final int scale) {
		MapStore store = this.store;
		MapRegion region = store.regionAtBlock(blockX, blockZ, false);
		if (region == null || !region.isChunkExplored(blockX >> 4, blockZ >> 4)) {
			this.sampleExplored = false;
			return;
		}

		this.sampleExplored = true;
		if (scale == 1) {
			int index = (blockZ & MapRegion.MASK) << MapRegion.SHIFT | (blockX & MapRegion.MASK);
			this.sampleColor = region.color(index);
			this.sampleHeight = region.height(index);
			this.sampleDepth = region.depth(index);
			return;
		}

		// A pixel never crosses a chunk (scale <= 16), so one region covers it.
		int[] counts = this.colorCounts;
		int[] order = this.colorOrder;
		int distinct = 0;
		double height = 0.0;
		int depth = 0;
		int area = scale * scale;
		for (int dx = 0; dx < scale; dx++) {
			for (int dz = 0; dz < scale; dz++) {
				int index = (blockZ + dz & MapRegion.MASK) << MapRegion.SHIFT | (blockX + dx & MapRegion.MASK);
				int color = region.color(index);
				if (counts[color]++ == 0) {
					order[distinct++] = color;
				}

				height += (double)region.height(index) / area;
				depth += region.depth(index);
			}
		}

		int best = MapColor.NONE.id;
		int bestCount = 0;
		for (int i = 0; i < distinct; i++) {
			int color = order[i];
			if (counts[color] > bestCount) {
				bestCount = counts[color];
				best = color;
			}

			counts[color] = 0;
		}

		if (store.hasCeiling()) {
			// Vanilla only looks at the corner block of the area in dimensions with a ceiling.
			best = region.color((blockZ & MapRegion.MASK) << MapRegion.SHIFT | (blockX & MapRegion.MASK));
		}

		this.sampleColor = best;
		this.sampleHeight = height;
		this.sampleDepth = depth / area;
	}

	private void fill(final MapTile tile, final int x0, final int y0, final int x1, final int y1) {
		var image = tile.texture.getPixels();
		int shift = tile.scaleShift;
		int scale = 1 << shift;
		int basePixelX = tile.tileX * TILE_SIZE;
		int basePixelZ = tile.tileZ * TILE_SIZE;
		for (int px = x0; px < x1; px++) {
			int imgX = basePixelX + px;
			int blockX = imgX << shift;
			// Start one row north so the first pixel can be shaded against its neighbour.
			this.sample(blockX, basePixelZ + y0 - 1 << shift, scale);
			double previousHeight = this.sampleExplored ? this.sampleHeight : Double.NaN;
			for (int py = y0; py < y1; py++) {
				int imgY = basePixelZ + py;
				this.sample(blockX, imgY << shift, scale);
				if (!this.sampleExplored) {
					image.setPixel(px, py, UNEXPLORED);
					previousHeight = Double.NaN;
					continue;
				}

				double height = this.sampleHeight;
				if (Double.isNaN(previousHeight)) {
					previousHeight = height;
				}

				int parity = imgX + imgY & 1;
				MapColor color = MapColor.byId(this.sampleColor);
				MapColor.Brightness brightness;
				if (color == MapColor.WATER) {
					double diff = this.sampleDepth * 0.1 + parity * 0.2;
					if (diff < 0.5) {
						brightness = MapColor.Brightness.HIGH;
					} else if (diff > 0.9) {
						brightness = MapColor.Brightness.LOW;
					} else {
						brightness = MapColor.Brightness.NORMAL;
					}
				} else {
					double diff = (height - previousHeight) * 4.0 / (scale + 4) + (parity - 0.5) * 0.4;
					if (diff > 0.6) {
						brightness = MapColor.Brightness.HIGH;
					} else if (diff < -0.6) {
						brightness = MapColor.Brightness.LOW;
					} else {
						brightness = MapColor.Brightness.NORMAL;
					}
				}

				previousHeight = height;
				// MapColor.NONE gives a transparent pixel, so the paper shows through like on a real map.
				image.setPixel(px, py, MapColor.getColorFromPackedId(color.getPackedId(brightness)));
			}
		}
	}

	public void close() {
		this.clear();
		this.store = null;
	}
}
