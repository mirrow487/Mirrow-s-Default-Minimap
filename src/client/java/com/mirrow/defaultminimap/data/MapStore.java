package com.mirrow.defaultminimap.data;

import com.mirrow.defaultminimap.MirrowsDefaultMinimapClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;

/**
 * All explored map data of one world + dimension, split into region files on disk.
 * Only accessed from the client (render) thread; disk writes happen on a background thread
 * using snapshots.
 */
public final class MapStore {
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Mirrow's Default Minimap IO");
		thread.setDaemon(true);
		return thread;
	});

	private final Path directory;
	private final boolean ceiling;
	private final Long2ObjectOpenHashMap<MapRegion> regions = new Long2ObjectOpenHashMap<>();
	/** Regions known to have no file on disk and no data yet. */
	private final LongOpenHashSet missing = new LongOpenHashSet();
	private MapRegion lastRegion;

	public MapStore(final Path directory, final boolean ceiling) {
		this.directory = directory;
		this.ceiling = ceiling;
	}

	public Path directory() {
		return this.directory;
	}

	/** True in dimensions with a ceiling (the Nether), where vanilla maps draw a noise pattern. */
	public boolean hasCeiling() {
		return this.ceiling;
	}

	private static long key(final int regionX, final int regionZ) {
		return ChunkPos.pack(regionX, regionZ);
	}

	/**
	 * Returns the region containing the given block, loading it from disk when needed.
	 * With {@code create == false} returns null for regions that were never explored.
	 */
	public MapRegion regionAtBlock(final int blockX, final int blockZ, final boolean create) {
		return this.region(blockX >> MapRegion.SHIFT, blockZ >> MapRegion.SHIFT, create);
	}

	public MapRegion region(final int regionX, final int regionZ, final boolean create) {
		MapRegion last = this.lastRegion;
		if (last != null && last.regionX == regionX && last.regionZ == regionZ) {
			return last;
		}

		long key = key(regionX, regionZ);
		MapRegion region = this.regions.get(key);
		if (region == null) {
			if (!this.missing.contains(key)) {
				region = this.load(regionX, regionZ);
				if (region != null) {
					this.regions.put(key, region);
				} else {
					this.missing.add(key);
				}
			}

			if (region == null) {
				if (!create) {
					return null;
				}

				region = new MapRegion(regionX, regionZ);
				this.regions.put(key, region);
				this.missing.remove(key);
			}
		}

		this.lastRegion = region;
		return region;
	}

	private MapRegion load(final int regionX, final int regionZ) {
		Path file = MapRegion.fileName(this.directory, regionX, regionZ);
		if (!Files.isRegularFile(file)) {
			return null;
		}

		try {
			return MapRegion.read(this.directory, regionX, regionZ);
		} catch (IOException e) {
			MirrowsDefaultMinimapClient.LOGGER.warn("Could not read map region {}", file, e);
			return null;
		}
	}

	public boolean isChunkExplored(final int chunkX, final int chunkZ) {
		MapRegion region = this.region(chunkX >> 5, chunkZ >> 5, false);
		return region != null && region.isChunkExplored(chunkX, chunkZ);
	}

	/** Saves every changed region. With {@code wait} the call blocks until everything is on disk. */
	public void save(final boolean wait) {
		List<MapRegion> snapshots = new ArrayList<>();
		for (Long2ObjectMap.Entry<MapRegion> entry : this.regions.long2ObjectEntrySet()) {
			MapRegion region = entry.getValue();
			if (region.dirty) {
				region.dirty = false;
				snapshots.add(region.snapshot());
			}
		}

		if (snapshots.isEmpty()) {
			return;
		}

		Path dir = this.directory;
		var future = IO.submit(() -> {
			for (MapRegion snapshot : snapshots) {
				try {
					snapshot.write(dir);
				} catch (IOException e) {
					MirrowsDefaultMinimapClient.LOGGER.warn("Could not save map region {}, {}", snapshot.regionX, snapshot.regionZ, e);
				}
			}
		});
		if (wait) {
			try {
				future.get(30, TimeUnit.SECONDS);
			} catch (Exception e) {
				MirrowsDefaultMinimapClient.LOGGER.warn("Timed out while saving minimap data", e);
			}
		}
	}

	/**
	 * Writes one scanned column. Returns true when anything visible changed.
	 */
	boolean setColumn(final MapRegion region, final int blockX, final int blockZ, final int color, final int height, final int depth) {
		int index = MapRegion.index(blockX, blockZ);
		byte c = (byte)color;
		short h = (short)height;
		byte d = (byte)Math.min(depth, 255);
		if (region.colors[index] == c && region.heights[index] == h && region.depths[index] == d) {
			return false;
		}

		region.colors[index] = c;
		region.heights[index] = h;
		region.depths[index] = d;
		region.dirty = true;
		return true;
	}
}
