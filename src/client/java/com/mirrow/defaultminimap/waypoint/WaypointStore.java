package com.mirrow.defaultminimap.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mirrow.defaultminimap.MirrowsDefaultMinimapClient;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.DyeColor;

/** Banner waypoints of one world + dimension, stored as {@code waypoints.json}. */
public final class WaypointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private final List<Waypoint> waypoints = new ArrayList<>();

	public WaypointStore(final Path directory) {
		this.file = directory.resolve("waypoints.json");
		this.load();
	}

	/** Live list, do not modify from outside. Use index loops while rendering. */
	public List<Waypoint> all() {
		return this.waypoints;
	}

	public Waypoint find(final int x, final int y, final int z) {
		for (int i = 0; i < this.waypoints.size(); i++) {
			Waypoint waypoint = this.waypoints.get(i);
			if (waypoint.isAt(x, y, z)) {
				return waypoint;
			}
		}

		return null;
	}

	/** Adds the banner, or removes it when it is already a waypoint. Returns true when added. */
	public boolean toggle(final int x, final int y, final int z, final DyeColor color, final String name) {
		Waypoint existing = this.find(x, y, z);
		boolean added;
		if (existing != null) {
			this.waypoints.remove(existing);
			added = false;
		} else {
			this.waypoints.add(new Waypoint(x, y, z, color, name));
			added = true;
		}

		this.save();
		return added;
	}

	public void remove(final Waypoint waypoint) {
		if (this.waypoints.remove(waypoint)) {
			this.save();
		}
	}

	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			List<Waypoint> loaded = GSON.fromJson(reader, new TypeToken<List<Waypoint>>() {}.getType());
			if (loaded != null) {
				for (Waypoint waypoint : loaded) {
					if (waypoint != null) {
						this.waypoints.add(waypoint);
					}
				}
			}
		} catch (IOException | JsonParseException e) {
			MirrowsDefaultMinimapClient.LOGGER.warn("Could not read waypoints from {}", this.file, e);
		}
	}

	public void save() {
		try {
			Files.createDirectories(this.file.getParent());
			try (Writer writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
				GSON.toJson(this.waypoints, writer);
			}
		} catch (IOException e) {
			MirrowsDefaultMinimapClient.LOGGER.warn("Could not save waypoints to {}", this.file, e);
		}
	}
}
