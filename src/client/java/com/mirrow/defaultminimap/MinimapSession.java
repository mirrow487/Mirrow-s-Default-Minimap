package com.mirrow.defaultminimap;

import com.mirrow.defaultminimap.data.ChunkScanner;
import com.mirrow.defaultminimap.data.MapStore;
import com.mirrow.defaultminimap.render.TileCache;
import com.mirrow.defaultminimap.waypoint.Waypoint;
import com.mirrow.defaultminimap.waypoint.WaypointStore;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Everything tied to the level the player is currently in: explored map data, waypoints,
 * the scan queue and the texture cache.
 */
public final class MinimapSession {
	/** Chunks scanned per client tick at most. */
	private static final int SCANS_PER_TICK = 6;
	/** Chunks around the player re-checked per tick to pick up block changes. */
	private static final int RESCANS_PER_TICK = 2;
	private static final int RESCAN_RADIUS = 8;
	private static final int SAVE_INTERVAL_TICKS = 20 * 60;
	private static final int BANNER_CHECK_INTERVAL_TICKS = 20;
	private static final double BANNER_CHECK_DISTANCE_SQR = 96.0 * 96.0;

	private final TileCache tiles = new TileCache();
	private final ChunkScanner scanner = new ChunkScanner();
	private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
	private final BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
	private ClientLevel level;
	private MapStore store;
	private WaypointStore waypoints;
	private int rescanCursor;
	private int ticks;

	public boolean isActive() {
		return this.store != null;
	}

	public TileCache tiles() {
		return this.tiles;
	}

	public WaypointStore waypoints() {
		return this.waypoints;
	}

	/** Switches to the given level (or closes when null). */
	public void ensureLevel(final ClientLevel newLevel) {
		if (newLevel == this.level) {
			return;
		}

		this.close();
		if (newLevel == null) {
			return;
		}

		this.level = newLevel;
		Path dir = FabricLoader.getInstance()
			.getConfigDir()
			.resolve(MirrowsDefaultMinimapClient.MOD_ID)
			.resolve(worldId(Minecraft.getInstance()))
			.resolve(dimensionId(newLevel.dimension().identifier()));
		this.store = new MapStore(dir, newLevel.dimensionType().hasCeiling());
		this.waypoints = new WaypointStore(dir);
		this.tiles.setStore(this.store);
	}

	public void close() {
		if (this.store != null) {
			this.store.save(true);
		}

		this.tiles.close();
		this.queue.clear();
		this.store = null;
		this.waypoints = null;
		this.level = null;
	}

	public void onChunkLoaded(final ClientLevel chunkLevel, final LevelChunk chunk) {
		this.ensureLevel(chunkLevel);
		ChunkPos pos = chunk.getPos();
		this.queue.add(ChunkPos.pack(pos.x(), pos.z()));
	}

	public void tick(final Minecraft minecraft) {
		this.ensureLevel(minecraft.level);
		if (this.store == null || minecraft.player == null) {
			return;
		}

		this.ticks++;
		this.queueRescans(minecraft.player);
		for (int i = 0; i < SCANS_PER_TICK && !this.queue.isEmpty(); i++) {
			long packed = this.queue.removeFirstLong();
			this.scanChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
		}

		if (this.ticks % BANNER_CHECK_INTERVAL_TICKS == 0) {
			this.checkBanners(minecraft.player);
		}

		if (this.ticks % SAVE_INTERVAL_TICKS == 0) {
			this.store.save(false);
		}
	}

	private void scanChunk(final int chunkX, final int chunkZ) {
		LevelChunk chunk = this.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk != null && this.scanner.scan(this.level, chunk, this.store)) {
			this.tiles.markChunkDirty(chunkX, chunkZ);
		}
	}

	/** Walks a square around the player a few chunks per tick so changed terrain gets redrawn. */
	private void queueRescans(final Player player) {
		int side = RESCAN_RADIUS * 2 + 1;
		int playerChunkX = player.getBlockX() >> 4;
		int playerChunkZ = player.getBlockZ() >> 4;
		for (int i = 0; i < RESCANS_PER_TICK; i++) {
			int index = this.rescanCursor++ % (side * side);
			if (this.rescanCursor >= side * side) {
				this.rescanCursor = 0;
			}

			int cx = playerChunkX + index % side - RESCAN_RADIUS;
			int cz = playerChunkZ + index / side - RESCAN_RADIUS;
			this.queue.add(ChunkPos.pack(cx, cz));
		}

		if (this.ticks % 10 == 0) {
			// The player's own chunk changes most often (digging, building).
			this.queue.addAndMoveToFirst(ChunkPos.pack(playerChunkX, playerChunkZ));
		}
	}

	/** Like vanilla's MapItemSavedData#checkBanners: drop waypoints whose banner is gone. */
	private void checkBanners(final Player player) {
		List<Waypoint> all = this.waypoints.all();
		for (int i = all.size() - 1; i >= 0; i--) {
			Waypoint waypoint = all.get(i);
			double dx = waypoint.x + 0.5 - player.getX();
			double dz = waypoint.z + 0.5 - player.getZ();
			if (dx * dx + dz * dz > BANNER_CHECK_DISTANCE_SQR) {
				continue;
			}

			if (this.level.getChunkSource().getChunk(waypoint.x >> 4, waypoint.z >> 4, ChunkStatus.FULL, false) == null) {
				continue;
			}

			this.checkPos.set(waypoint.x, waypoint.y, waypoint.z);
			if (this.level.getBlockEntity(this.checkPos) instanceof BannerBlockEntity banner) {
				String name = customName(banner);
				if (banner.getBaseColor() != waypoint.dyeColor() || !java.util.Objects.equals(name, waypoint.name)) {
					waypoint.update(banner.getBaseColor(), name);
					this.waypoints.save();
				}
			} else {
				this.waypoints.remove(waypoint);
			}
		}
	}

	/** Toggles a banner waypoint. Returns the action bar message to show. */
	public Component toggleBanner(final BannerBlockEntity banner) {
		if (this.waypoints == null) {
			return null;
		}

		BlockPos pos = banner.getBlockPos();
		boolean added = this.waypoints.toggle(pos.getX(), pos.getY(), pos.getZ(), banner.getBaseColor(), customName(banner));
		return Component.translatable(added ? "message.mirrows_default_minimap.waypoint_added" : "message.mirrows_default_minimap.waypoint_removed");
	}

	private static String customName(final BannerBlockEntity banner) {
		Component name = banner.getCustomName();
		return name == null ? null : name.getString();
	}

	private static String worldId(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path folder = root.getFileName();
			return "singleplayer_" + sanitize(folder != null ? folder.toString() : server.getWorldData().getLevelName());
		}

		ServerData data = minecraft.getCurrentServer();
		if (data != null) {
			return (data.isRealm() ? "realms_" : "server_") + sanitize(data.ip);
		}

		return "unknown";
	}

	private static String dimensionId(final Identifier id) {
		return sanitize(id.getNamespace() + "_" + id.getPath());
	}

	private static String sanitize(final String value) {
		String cleaned = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return cleaned.isEmpty() ? "unnamed" : cleaned;
	}
}
