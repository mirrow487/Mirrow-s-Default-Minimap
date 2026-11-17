package com.mirrow.defaultminimap.waypoint;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

/** A banner the player marked. Rendered with the vanilla banner map decoration of the same colour. */
public final class Waypoint {
	public int x;
	public int y;
	public int z;
	/** Serialized {@link DyeColor} name, e.g. "red". */
	public String color = "white";
	/** Custom name of the banner, or null. */
	public String name;

	private transient Identifier texture;
	private transient Component label;
	private transient boolean labelResolved;

	public Waypoint() {
	}

	public Waypoint(final int x, final int y, final int z, final DyeColor color, final String name) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.color = color.getSerializedName();
		this.name = name;
	}

	public boolean isAt(final int x, final int y, final int z) {
		return this.x == x && this.y == y && this.z == z;
	}

	public DyeColor dyeColor() {
		DyeColor color = DyeColor.byName(this.color, DyeColor.WHITE);
		return color == null ? DyeColor.WHITE : color;
	}

	/** {@code textures/map/decorations/<colour>_banner.png} from the game's own resources. */
	public Identifier texture() {
		if (this.texture == null) {
			this.texture = Identifier.withDefaultNamespace("textures/map/decorations/" + this.dyeColor().getSerializedName() + "_banner.png");
		}

		return this.texture;
	}

	public Component label() {
		if (!this.labelResolved) {
			this.labelResolved = true;
			this.label = this.name == null || this.name.isEmpty() ? null : Component.literal(this.name);
		}

		return this.label;
	}

	public void update(final DyeColor color, final String name) {
		this.color = color.getSerializedName();
		this.name = name;
		this.texture = null;
		this.labelResolved = false;
	}
}
