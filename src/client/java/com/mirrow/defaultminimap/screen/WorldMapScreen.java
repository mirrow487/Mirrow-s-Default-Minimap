package com.mirrow.defaultminimap.screen;

import com.mirrow.defaultminimap.MinimapSession;
import com.mirrow.defaultminimap.MirrowsDefaultMinimapClient;
import com.mirrow.defaultminimap.render.MapDrawer;
import com.mirrow.defaultminimap.render.TileCache;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Full screen world map in the same vanilla map style. Drag to pan, scroll to zoom.
 */
public final class WorldMapScreen extends Screen {
	/** Blocks per GUI pixel for every zoom step. */
	private static final float[] ZOOM_LEVELS = {0.25F, 0.5F, 1.0F, 2.0F, 4.0F, 8.0F, 16.0F};
	private static final int DEFAULT_ZOOM = 2;
	private static final int TILE_BUILD_BUDGET = 8;
	private static final int OUTER_MARGIN = 8;

	private final MapDrawer.View view = new MapDrawer.View();
	private double centerX;
	private double centerZ;
	private int zoom = DEFAULT_ZOOM;
	private boolean centered;
	private Component zoomLabel;
	private float texel = 3.0F;

	/** Size of one paper texel: square, scaled with the screen so the border stays in proportion. */
	private static float paperTexel(final int width, final int height) {
		return Mth.clamp(Math.min(width, height) / 96.0F, 2.0F, 6.0F);
	}

	public WorldMapScreen() {
		super(Component.translatable("screen.mirrows_default_minimap.world_map"));
		this.updateZoomLabel();
	}

	@Override
	protected void init() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (!this.centered && player != null) {
			this.centered = true;
			this.centerX = player.getX();
			this.centerZ = player.getZ();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void updateZoomLabel() {
		float bpp = ZOOM_LEVELS[this.zoom];
		String text = bpp >= 1.0F ? "1:" + (int)bpp : (int)(1.0F / bpp) + ":1";
		this.zoomLabel = Component.literal(text);
	}

	private void layoutView() {
		int frameX = OUTER_MARGIN;
		int frameY = OUTER_MARGIN;
		int frameW = this.width - OUTER_MARGIN * 2;
		int frameH = this.height - OUTER_MARGIN * 2;
		this.texel = paperTexel(frameW, frameH);
		int border = Math.round(MapDrawer.PAPER_INSET * this.texel);
		float bpp = ZOOM_LEVELS[this.zoom];
		int shift = bpp <= 1.0F ? 0 : Math.min(TileCache.MAX_SCALE_SHIFT, Integer.numberOfTrailingZeros((int)bpp));
		MapDrawer.View view = this.view;
		view.x = frameX + border;
		view.y = frameY + border;
		view.width = frameW - border * 2;
		view.height = frameH - border * 2;
		view.centerX = this.centerX;
		view.centerZ = this.centerZ;
		view.scaleShift = shift;
		view.pixelSize = (1 << shift) / bpp;
		view.iconSize = 12.0F * Mth.clamp(view.pixelSize, 1.0F, 2.0F);
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		graphics.fill(0, 0, this.width, this.height, 0xB0000000);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		MinimapSession session = MirrowsDefaultMinimapClient.session();
		Minecraft minecraft = Minecraft.getInstance();
		if (!session.isActive() || minecraft.player == null) {
			return;
		}

		this.layoutView();
		MapDrawer.View view = this.view;
		TileCache tiles = session.tiles();
		tiles.beginFrame(TILE_BUILD_BUDGET);
		MapDrawer.drawPaper(graphics, OUTER_MARGIN, OUTER_MARGIN, this.width - OUTER_MARGIN * 2, this.height - OUTER_MARGIN * 2, this.texel);
		MapDrawer.drawTiles(graphics, tiles, view);
		graphics.enableScissor(view.x - 4, view.y - 4, view.x + view.width + 4, view.y + view.height + 4);
		MapDrawer.drawWaypoints(graphics, view, session.waypoints().all());
		MapDrawer.drawPlayer(graphics, view, minecraft.player, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		graphics.disableScissor();

		graphics.text(this.font, this.zoomLabel, view.x + 4, view.y + view.height - this.font.lineHeight - 3, 0xFFFFFFFF, true);
		if (MirrowsDefaultMinimapClient.config().showCoordinates && mouseX >= view.x && mouseY >= view.y && mouseX < view.x + view.width && mouseY < view.y + view.height) {
			int bx = Mth.floor(view.toBlockX(mouseX));
			int bz = Mth.floor(view.toBlockZ(mouseY));
			String text = bx + ", " + bz;
			graphics.text(this.font, text, view.x + view.width - this.font.width(text) - 4, view.y + view.height - this.font.lineHeight - 3, 0xFFFFFFFF, true);
		}
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			float bpp = ZOOM_LEVELS[this.zoom];
			this.centerX -= dx * bpp;
			this.centerZ -= dy * bpp;
			return true;
		}

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		if (scrollY == 0.0) {
			return false;
		}

		this.zoomAround(scrollY > 0.0 ? -1 : 1, x, y);
		return true;
	}

	/** Changes zoom while keeping the block under the cursor in place. */
	private void zoomAround(final int step, final double guiX, final double guiY) {
		int newZoom = Mth.clamp(this.zoom + step, 0, ZOOM_LEVELS.length - 1);
		if (newZoom == this.zoom) {
			return;
		}

		this.layoutView();
		double focusX = this.view.toBlockX(guiX);
		double focusZ = this.view.toBlockZ(guiY);
		double offsetX = guiX - (this.view.x + this.view.width / 2.0);
		double offsetY = guiY - (this.view.y + this.view.height / 2.0);
		this.zoom = newZoom;
		float bpp = ZOOM_LEVELS[this.zoom];
		this.centerX = focusX - offsetX * bpp;
		this.centerZ = focusZ - offsetY * bpp;
		this.updateZoomLabel();
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (MirrowsDefaultMinimapClient.WORLD_MAP_KEY.matches(event)) {
			this.onClose();
			return true;
		}

		if (MirrowsDefaultMinimapClient.ZOOM_IN_KEY.matches(event)) {
			this.zoomAround(-1, this.width / 2.0, this.height / 2.0);
			return true;
		}

		if (MirrowsDefaultMinimapClient.ZOOM_OUT_KEY.matches(event)) {
			this.zoomAround(1, this.width / 2.0, this.height / 2.0);
			return true;
		}

		return super.keyPressed(event);
	}
}
