package com.mirrow.defaultminimap;

import com.mirrow.defaultminimap.config.MinimapConfig;
import com.mirrow.defaultminimap.render.MinimapHud;
import com.mirrow.defaultminimap.screen.WorldMapScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MirrowsDefaultMinimapClient implements ClientModInitializer {
	public static final String MOD_ID = "mirrows_default_minimap";
	public static final Logger LOGGER = LoggerFactory.getLogger("Mirrow's Default Minimap");

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("minimap"));
	public static final KeyMapping TOGGLE_KEY = new KeyMapping("key.mirrows_default_minimap.toggle", InputConstants.KEY_M, CATEGORY);
	public static final KeyMapping ZOOM_IN_KEY = new KeyMapping("key.mirrows_default_minimap.zoom_in", InputConstants.KEY_EQUALS, CATEGORY);
	public static final KeyMapping ZOOM_OUT_KEY = new KeyMapping("key.mirrows_default_minimap.zoom_out", InputConstants.KEY_MINUS, CATEGORY);
	public static final KeyMapping WORLD_MAP_KEY = new KeyMapping("key.mirrows_default_minimap.world_map", InputConstants.KEY_J, CATEGORY);

	private static MinimapConfig config;
	private static final MinimapSession SESSION = new MinimapSession();
	/** Banner toggled by the current right click; cleared when the use key is released. */
	private static BlockPos lastToggledBanner;

	public static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static MinimapConfig config() {
		return config;
	}

	public static MinimapSession session() {
		return SESSION;
	}

	@Override
	public void onInitializeClient() {
		config = MinimapConfig.load();

		KeyMappingHelper.registerKeyMapping(TOGGLE_KEY);
		KeyMappingHelper.registerKeyMapping(ZOOM_IN_KEY);
		KeyMappingHelper.registerKeyMapping(ZOOM_OUT_KEY);
		KeyMappingHelper.registerKeyMapping(WORLD_MAP_KEY);

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("minimap"), new MinimapHud());

		ClientChunkEvents.CHUNK_LOAD.register(SESSION::onChunkLoaded);
		ClientTickEvents.END_CLIENT_TICK.register(MirrowsDefaultMinimapClient::onEndTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(SESSION::close));
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> SESSION.close());
		UseBlockCallback.EVENT.register(MirrowsDefaultMinimapClient::onUseBlock);
	}

	private static void onEndTick(final Minecraft minecraft) {
		SESSION.tick(minecraft);

		if (!minecraft.options.keyUse.isDown()) {
			lastToggledBanner = null;
		}

		while (TOGGLE_KEY.consumeClick()) {
			config.enabled = !config.enabled;
			config.save();
		}

		while (ZOOM_IN_KEY.consumeClick()) {
			if (config.zoomIn()) {
				config.save();
			}
		}

		while (ZOOM_OUT_KEY.consumeClick()) {
			if (config.zoomOut()) {
				config.save();
			}
		}

		while (WORLD_MAP_KEY.consumeClick()) {
			if (minecraft.player != null && SESSION.isActive() && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new WorldMapScreen());
			}
		}
	}

	/**
	 * Right clicking a banner with an empty hand or a map toggles it as a waypoint.
	 * Always passes so vanilla keeps its own behaviour (e.g. marking the banner on a held map).
	 */
	private static InteractionResult onUseBlock(final net.minecraft.world.entity.player.Player player, final net.minecraft.world.level.Level level,
		final InteractionHand hand, final net.minecraft.world.phys.BlockHitResult hitResult) {
		if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()) {
			return InteractionResult.PASS;
		}

		ItemStack stack = player.getItemInHand(hand);
		if (!stack.isEmpty() && !stack.is(Items.FILLED_MAP) && !stack.is(Items.MAP)) {
			return InteractionResult.PASS;
		}

		BlockPos pos = hitResult.getBlockPos();
		if (!level.getBlockState(pos).is(BlockTags.BANNERS) || !(level.getBlockEntity(pos) instanceof BannerBlockEntity banner)) {
			return InteractionResult.PASS;
		}

		// Holding right click repeats the interaction every few ticks; only toggle once per press.
		if (pos.equals(lastToggledBanner)) {
			return InteractionResult.PASS;
		}

		lastToggledBanner = pos.immutable();
		Component message = SESSION.toggleBanner(banner);
		if (message != null) {
			player.sendOverlayMessage(message);
		}

		return InteractionResult.PASS;
	}
}
