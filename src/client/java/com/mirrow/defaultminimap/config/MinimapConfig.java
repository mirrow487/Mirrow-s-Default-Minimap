package com.mirrow.defaultminimap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mirrow.defaultminimap.MirrowsDefaultMinimapClient;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Simple JSON config stored at {@code config/mirrows_default_minimap.json}.
 */
public final class MinimapConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int[] ALLOWED_SCALES = {1, 2, 4};

	public enum Position {
		TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT
	}

	/** Whether the minimap HUD is visible. Toggled with M. */
	public boolean enabled = true;
	/** Corner of the screen the minimap is anchored to. */
	public String position = Position.TOP_RIGHT.name();
	/** Size of the map area (without the paper frame) in GUI pixels. */
	public int size = 96;
	/** Blocks per map pixel: 1, 2 or 4. */
	public int scale = 1;
	/** Distance from the screen edge in GUI pixels. */
	public int margin = 4;
	/** Show a coordinate line under the minimap. Off by default for clean recordings. */
	public boolean showCoordinates = false;

	private transient Path file;
	private transient Position cachedPosition;
	private transient String cachedPositionSource;

	public static MinimapConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(MirrowsDefaultMinimapClient.MOD_ID + ".json");
		MinimapConfig config = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, MinimapConfig.class);
			} catch (IOException | JsonParseException e) {
				MirrowsDefaultMinimapClient.LOGGER.warn("Could not read minimap config, using defaults", e);
			}
		}

		if (config == null) {
			config = new MinimapConfig();
		}

		config.file = file;
		config.sanitize();
		config.save();
		return config;
	}

	public void save() {
		if (this.file == null) {
			return;
		}

		try {
			Files.createDirectories(this.file.getParent());
			try (Writer writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			MirrowsDefaultMinimapClient.LOGGER.warn("Could not save minimap config", e);
		}
	}

	private void sanitize() {
		this.position = this.getPosition().name();
		this.size = Math.max(32, Math.min(512, this.size));
		this.margin = Math.max(0, Math.min(64, this.margin));
		boolean validScale = false;
		for (int allowed : ALLOWED_SCALES) {
			validScale |= allowed == this.scale;
		}

		if (!validScale) {
			this.scale = 1;
		}
	}

	/** Parsed position; cached so the HUD does not parse a string every frame. */
	public Position getPosition() {
		if (this.cachedPosition == null || this.cachedPositionSource != this.position) {
			Position parsed = Position.TOP_RIGHT;
			if (this.position != null) {
				try {
					parsed = Position.valueOf(this.position.trim().toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException ignored) {
				}
			}

			this.cachedPosition = parsed;
			this.cachedPositionSource = this.position;
		}

		return this.cachedPosition;
	}

	/** Zoom in: fewer blocks per pixel. Returns true if the value changed. */
	public boolean zoomIn() {
		if (this.scale > 1) {
			this.scale /= 2;
			return true;
		}

		return false;
	}

	/** Zoom out: more blocks per pixel. Returns true if the value changed. */
	public boolean zoomOut() {
		if (this.scale < 4) {
			this.scale *= 2;
			return true;
		}

		return false;
	}
}
