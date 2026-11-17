package com.mirrow.defaultminimap.render;

import com.mirrow.defaultminimap.MinimapSession;
import com.mirrow.defaultminimap.MirrowsDefaultMinimapClient;
import com.mirrow.defaultminimap.config.MinimapConfig;
import com.mirrow.defaultminimap.screen.WorldMapScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** The minimap in the corner of the screen. Always north-up and centred on the player. */
public final class MinimapHud implements HudElement {
	/** Map pixels visible across the minimap, the same as a vanilla map. */
	private static final int MAP_PIXELS = 128;
	private static final int TILE_BUILD_BUDGET = 4;

	private final MapDrawer.View view = new MapDrawer.View();
	private final StringBuilder coordinates = new StringBuilder(48);
	private int lastX = Integer.MIN_VALUE;
	private int lastY = Integer.MIN_VALUE;
	private int lastZ = Integer.MIN_VALUE;
	private String coordinateText = "";

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		MinimapConfig config = MirrowsDefaultMinimapClient.config();
		MinimapSession session = MirrowsDefaultMinimapClient.session();
		LocalPlayer player = minecraft.player;
		if (!config.enabled || player == null || !session.isActive() || minecraft.gui.screen() instanceof WorldMapScreen) {
			return;
		}

		int size = config.size;
		int frame = Math.max(1, Math.round(size * MapDrawer.FRAME_RATIO));
		int total = size + frame * 2;
		int margin = config.margin;
		int x;
		int y;
		switch (config.getPosition()) {
			case TOP_LEFT -> {
				x = margin;
				y = margin;
			}
			case BOTTOM_LEFT -> {
				x = margin;
				y = graphics.guiHeight() - total - margin;
			}
			case BOTTOM_RIGHT -> {
				x = graphics.guiWidth() - total - margin;
				y = graphics.guiHeight() - total - margin;
			}
			default -> {
				x = graphics.guiWidth() - total - margin;
				y = margin;
			}
		}

		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		int shift = Integer.numberOfTrailingZeros(config.scale);
		MapDrawer.View view = this.view;
		view.x = x + frame;
		view.y = y + frame;
		view.width = size;
		view.height = size;
		view.centerX = Mth.lerp(partialTick, player.xo, player.getX());
		view.centerZ = Mth.lerp(partialTick, player.zo, player.getZ());
		view.scaleShift = shift;
		view.pixelSize = size / (float)MAP_PIXELS;
		view.iconSize = 8.0F * view.pixelSize;

		TileCache tiles = session.tiles();
		tiles.beginFrame(TILE_BUILD_BUDGET);
		MapDrawer.drawBackground(graphics, x, y, total, total);
		MapDrawer.drawTiles(graphics, tiles, view);
		MapDrawer.drawWaypoints(graphics, view, session.waypoints().all());
		MapDrawer.drawPlayer(graphics, view, player, partialTick);

		if (config.showCoordinates) {
			Font font = minecraft.font;
			String text = this.coordinateText(player.getBlockX(), player.getBlockY(), player.getBlockZ());
			int textWidth = font.width(text);
			int textX = x + (total - textWidth) / 2;
			int textY = config.getPosition() == MinimapConfig.Position.BOTTOM_LEFT || config.getPosition() == MinimapConfig.Position.BOTTOM_RIGHT
				? y - font.lineHeight - 1
				: y + total + 2;
			graphics.text(font, text, textX, textY, 0xFFFFFFFF, true);
		}
	}

	/** Rebuilds the string only when the block position changes. */
	private String coordinateText(final int bx, final int by, final int bz) {
		if (bx != this.lastX || by != this.lastY || bz != this.lastZ) {
			this.lastX = bx;
			this.lastY = by;
			this.lastZ = bz;
			StringBuilder builder = this.coordinates;
			builder.setLength(0);
			builder.append(bx).append(", ").append(by).append(", ").append(bz);
			this.coordinateText = builder.toString();
		}

		return this.coordinateText;
	}
}
