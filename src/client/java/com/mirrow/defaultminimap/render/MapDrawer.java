package com.mirrow.defaultminimap.render;

import com.mirrow.defaultminimap.waypoint.Waypoint;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix3x2fStack;

/**
 * Shared drawing code for the HUD minimap and the full screen world map.
 * Everything uses textures straight from the game's own resources.
 */
public final class MapDrawer {
	public static final Identifier MAP_BACKGROUND = Identifier.withDefaultNamespace("textures/map/map_background.png");
	public static final Identifier PLAYER = Identifier.withDefaultNamespace("textures/map/decorations/player.png");
	public static final Identifier PLAYER_OFF_MAP = Identifier.withDefaultNamespace("textures/map/decorations/player_off_map.png");
	public static final Identifier PLAYER_OFF_LIMITS = Identifier.withDefaultNamespace("textures/map/decorations/player_off_limits.png");
	/** Vanilla draws the 128 px map inside a 142 px paper (7 px border on each side). */
	public static final float FRAME_RATIO = 7.0F / 128.0F;
	private static final int UNEXPLORED = 0xFF000000;

	private MapDrawer() {
	}

	/** Mutable view description, reused every frame to avoid allocations. */
	public static final class View {
		public int x;
		public int y;
		public int width;
		public int height;
		public double centerX;
		public double centerZ;
		public int scaleShift;
		/** GUI pixels per map pixel. */
		public float pixelSize;
		/** GUI size of a decoration icon (vanilla: 8 map pixels). */
		public float iconSize;

		public float toGuiX(final double blockX) {
			return (float)(this.x + this.width / 2.0 + (blockX - this.centerX) / (1 << this.scaleShift) * this.pixelSize);
		}

		public float toGuiY(final double blockZ) {
			return (float)(this.y + this.height / 2.0 + (blockZ - this.centerZ) / (1 << this.scaleShift) * this.pixelSize);
		}

		public double toBlockX(final double guiX) {
			return this.centerX + (guiX - this.x - this.width / 2.0) / this.pixelSize * (1 << this.scaleShift);
		}

		public double toBlockZ(final double guiY) {
			return this.centerZ + (guiY - this.y - this.height / 2.0) / this.pixelSize * (1 << this.scaleShift);
		}
	}

	/** Paper background stretched over the given rectangle, like the vanilla map item. */
	public static void drawBackground(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int height) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, MAP_BACKGROUND, x, y, 0.0F, 0.0F, width, height, 64, 64, 64, 64);
	}

	/** Draws the map tiles that cover the view, clipped to it. */
	public static void drawTiles(final GuiGraphicsExtractor graphics, final TileCache cache, final View view) {
		int shift = view.scaleShift;
		double scale = 1 << shift;
		float pixelSize = view.pixelSize;
		double centerPixelX = view.centerX / scale;
		double centerPixelZ = view.centerZ / scale;
		double halfW = view.width / 2.0 / pixelSize;
		double halfH = view.height / 2.0 / pixelSize;
		int tx0 = Mth.floor((centerPixelX - halfW) / TileCache.TILE_SIZE);
		int tx1 = Mth.floor((centerPixelX + halfW) / TileCache.TILE_SIZE);
		int tz0 = Mth.floor((centerPixelZ - halfH) / TileCache.TILE_SIZE);
		int tz1 = Mth.floor((centerPixelZ + halfH) / TileCache.TILE_SIZE);
		float originX = view.x + view.width / 2.0F;
		float originY = view.y + view.height / 2.0F;

		graphics.enableScissor(view.x, view.y, view.x + view.width, view.y + view.height);
		Matrix3x2fStack pose = graphics.pose();
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				MapTile tile = cache.get(shift, tx, tz);
				pose.pushMatrix();
				pose.translate(
					originX + (float)((tx * (double)TileCache.TILE_SIZE - centerPixelX) * pixelSize),
					originY + (float)((tz * (double)TileCache.TILE_SIZE - centerPixelZ) * pixelSize)
				);
				pose.scale(pixelSize, pixelSize);
				if (tile.texture != null) {
					graphics.blit(tile.texture.getTextureView(), tile.texture.getSampler(), 0, 0, TileCache.TILE_SIZE, TileCache.TILE_SIZE, 0.0F, 1.0F, 0.0F, 1.0F);
				} else {
					// Empty (never explored) or not built yet: unexplored land is black.
					graphics.fill(0, 0, TileCache.TILE_SIZE, TileCache.TILE_SIZE, UNEXPLORED);
				}

				pose.popMatrix();
			}
		}

		graphics.disableScissor();
	}

	/** Vanilla {@code MapItemSavedData#calculateRotation} for a yaw. */
	public static int vanillaRotation(final float yRot) {
		double adjusted = yRot < 0.0F ? yRot - 8.0 : yRot + 8.0;
		return (byte)(adjusted * 16.0 / 360.0);
	}

	/**
	 * Draws a map decoration texture the way vanilla's MapRenderer does: an 8x8 icon,
	 * rotated in 22.5 degree steps, with vanilla's flipped quad and half pixel offset.
	 */
	public static void drawDecoration(final GuiGraphicsExtractor graphics, final Identifier texture, final float x, final float y, final int rot, final float size) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.rotate(rot * Mth.TWO_PI / 16.0F);
		float unit = size / 8.0F;
		pose.scale(unit, unit);
		pose.translate(-0.5F, 0.5F);
		// Vanilla maps the texture's top row to the quad's +Y side, so the sprite is drawn flipped.
		graphics.blit(texture, -4, -4, 4, 4, 0.0F, 1.0F, 1.0F, 0.0F);
		pose.popMatrix();
	}

	/** Small name under a decoration, like named banners on a vanilla map. */
	public static void drawLabel(final GuiGraphicsExtractor graphics, final Component label, final float x, final float y, final float iconSize) {
		Font font = Minecraft.getInstance().font;
		int width = font.width(label);
		if (width <= 0) {
			return;
		}

		float unit = iconSize / 8.0F;
		float scale = Mth.clamp(25.0F / width, 0.0F, 6.0F / 9.0F) * unit;
		scale = Math.max(scale, 0.5F);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x - width * scale / 2.0F, y + 4.0F * unit);
		pose.scale(scale, scale);
		graphics.fill(-1, -1, width, 9, 0x80000000);
		graphics.text(font, label, 0, 0, 0xFFFFFFFF, false);
		pose.popMatrix();
	}

	/**
	 * Draws banner waypoints. Waypoints outside the view are pinned to the view's edge,
	 * in their own direction from the centre.
	 */
	public static void drawWaypoints(final GuiGraphicsExtractor graphics, final View view, final List<Waypoint> waypoints) {
		float centerX = view.x + view.width / 2.0F;
		float centerY = view.y + view.height / 2.0F;
		float halfW = view.width / 2.0F;
		float halfH = view.height / 2.0F;
		// Banners use the fixed vanilla rotation for yRot 180.
		int rot = vanillaRotation(180.0F);
		for (int i = 0; i < waypoints.size(); i++) {
			Waypoint waypoint = waypoints.get(i);
			float dx = view.toGuiX(waypoint.x + 0.5) - centerX;
			float dy = view.toGuiY(waypoint.z + 0.5) - centerY;
			float t = Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
			boolean outside = t > 1.0F;
			if (outside) {
				dx /= t;
				dy /= t;
			}

			float gx = centerX + dx;
			float gy = centerY + dy;
			drawDecoration(graphics, waypoint.texture(), gx, gy, rot, view.iconSize);
			Component label = waypoint.label();
			if (label != null && !outside) {
				drawLabel(graphics, label, gx, gy, view.iconSize);
			}
		}
	}

	/**
	 * Draws the player arrow. Like vanilla, a player outside the map is shown on the edge as the
	 * small "off map" marker (or "off limits" marker when very far away).
	 */
	public static void drawPlayer(final GuiGraphicsExtractor graphics, final View view, final Player player, final float partialTick) {
		double px = Mth.lerp(partialTick, player.xo, player.getX());
		double pz = Mth.lerp(partialTick, player.zo, player.getZ());
		float centerX = view.x + view.width / 2.0F;
		float centerY = view.y + view.height / 2.0F;
		float halfW = view.width / 2.0F;
		float halfH = view.height / 2.0F;
		float dx = view.toGuiX(px) - centerX;
		float dy = view.toGuiY(pz) - centerY;
		float t = Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
		Identifier texture = PLAYER;
		if (t > 1.0F) {
			// Vanilla: within 320 map pixels the off-map dot, beyond that the smaller off-limits dot.
			float mapPixels = Math.max(Math.abs(dx), Math.abs(dy)) / view.pixelSize;
			texture = mapPixels < 320.0F ? PLAYER_OFF_MAP : PLAYER_OFF_LIMITS;
			dx /= t;
			dy /= t;
		}

		drawDecoration(graphics, texture, centerX + dx, centerY + dy, vanillaRotation(player.getYRot(partialTick)), view.iconSize);
	}
}
