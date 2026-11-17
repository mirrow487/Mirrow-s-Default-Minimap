package com.mirrow.defaultminimap.data;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 32x32 chunks (512x512 block columns) of explored map data.
 * Per column we keep the vanilla map colour id, the surface height and the water depth,
 * which is everything vanilla's MapItem needs to compute the final shaded pixel at any scale.
 */
public final class MapRegion {
	public static final int SHIFT = 9;
	public static final int SIZE = 1 << SHIFT;
	public static final int MASK = SIZE - 1;
	public static final int CHUNKS = SIZE >> 4;
	private static final int AREA = SIZE * SIZE;
	private static final int MAGIC = 0x4D44_4D4D;
	private static final int VERSION = 1;

	public final int regionX;
	public final int regionZ;
	final byte[] colors;
	final short[] heights;
	final byte[] depths;
	final long[] explored;
	private int exploredCount;
	boolean dirty;

	MapRegion(final int regionX, final int regionZ) {
		this(regionX, regionZ, new byte[AREA], new short[AREA], new byte[AREA], new long[CHUNKS * CHUNKS / 64]);
	}

	private MapRegion(final int regionX, final int regionZ, final byte[] colors, final short[] heights, final byte[] depths, final long[] explored) {
		this.regionX = regionX;
		this.regionZ = regionZ;
		this.colors = colors;
		this.heights = heights;
		this.depths = depths;
		this.explored = explored;
		for (long bits : explored) {
			this.exploredCount += Long.bitCount(bits);
		}
	}

	static int index(final int blockX, final int blockZ) {
		return (blockZ & MASK) << SHIFT | (blockX & MASK);
	}

	public boolean isChunkExplored(final int chunkX, final int chunkZ) {
		int i = (chunkZ & (CHUNKS - 1)) * CHUNKS + (chunkX & (CHUNKS - 1));
		return (this.explored[i >>> 6] >>> (i & 63) & 1L) != 0L;
	}

	void setChunkExplored(final int chunkX, final int chunkZ) {
		int i = (chunkZ & (CHUNKS - 1)) * CHUNKS + (chunkX & (CHUNKS - 1));
		long bit = 1L << (i & 63);
		if ((this.explored[i >>> 6] & bit) == 0L) {
			this.explored[i >>> 6] |= bit;
			this.exploredCount++;
			this.dirty = true;
		}
	}

	public boolean hasAnyExplored() {
		return this.exploredCount > 0;
	}

	public int color(final int index) {
		return this.colors[index] & 63;
	}

	public int height(final int index) {
		return this.heights[index];
	}

	public int depth(final int index) {
		return this.depths[index] & 0xFF;
	}

	/** A copy that can be written to disk on another thread. */
	MapRegion snapshot() {
		return new MapRegion(this.regionX, this.regionZ, this.colors.clone(), this.heights.clone(), this.depths.clone(), this.explored.clone());
	}

	static Path fileName(final Path dir, final int regionX, final int regionZ) {
		return dir.resolve("r." + regionX + "." + regionZ + ".mdm");
	}

	void write(final Path dir) throws IOException {
		Files.createDirectories(dir);
		Path target = fileName(dir, this.regionX, this.regionZ);
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp), 1 << 16)))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			for (long bits : this.explored) {
				out.writeLong(bits);
			}

			out.write(this.colors);
			ByteBuffer buffer = ByteBuffer.allocate(AREA * 2).order(ByteOrder.BIG_ENDIAN);
			buffer.asShortBuffer().put(this.heights);
			out.write(buffer.array());
			out.write(this.depths);
		}

		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static MapRegion read(final Path dir, final int regionX, final int regionZ) throws IOException {
		Path file = fileName(dir, regionX, regionZ);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16)))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION) {
				throw new IOException("Unknown region format in " + file);
			}

			long[] explored = new long[CHUNKS * CHUNKS / 64];
			for (int i = 0; i < explored.length; i++) {
				explored[i] = in.readLong();
			}

			byte[] colors = new byte[AREA];
			in.readFully(colors);
			byte[] heightBytes = new byte[AREA * 2];
			in.readFully(heightBytes);
			short[] heights = new short[AREA];
			ByteBuffer.wrap(heightBytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(heights);
			byte[] depths = new byte[AREA];
			in.readFully(depths);
			return new MapRegion(regionX, regionZ, colors, heights, depths, explored);
		}
	}
}
