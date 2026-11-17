package com.mirrow.defaultminimap.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

/**
 * Reads the surface of a loaded chunk exactly the way vanilla's {@code MapItem#update} does,
 * one column at a time (the 1:1 case). The result is stored per column so that every zoom
 * level can be shaded later with vanilla's averaging rules.
 */
public final class ChunkScanner {
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
	private int scannedColor;
	private int scannedHeight;
	private int scannedDepth;

	/**
	 * Scans the chunk into the store. Returns true when the stored data changed
	 * (or the chunk was explored for the first time).
	 */
	public boolean scan(final Level level, final LevelChunk chunk, final MapStore store) {
		if (chunk.isEmpty()) {
			return false;
		}

		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		int minX = chunkX << 4;
		int minZ = chunkZ << 4;
		MapRegion region = store.regionAtBlock(minX, minZ, true);
		boolean firstTime = !region.isChunkExplored(chunkX, chunkZ);
		boolean changed = false;
		boolean ceiling = store.hasCeiling();

		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int x = minX + dx;
				int z = minZ + dz;
				if (ceiling) {
					this.scanCeilingColumn(level, x, z);
				} else {
					this.scanColumn(level, chunk, x, z);
				}

				changed |= store.setColumn(region, x, z, this.scannedColor, this.scannedHeight, this.scannedDepth);
			}
		}

		region.setChunkExplored(chunkX, chunkZ);
		return changed || firstTime;
	}

	/** Vanilla draws a dirt/stone noise pattern instead of the real terrain in dimensions with a ceiling. */
	private void scanCeilingColumn(final Level level, final int x, final int z) {
		int ceilingNoise = x + z * 231871;
		ceilingNoise = ceilingNoise * ceilingNoise * 31287121 + ceilingNoise * 11;
		MapColor color = (ceilingNoise >> 20 & 1) == 0
			? Blocks.DIRT.defaultBlockState().getMapColor(level, BlockPos.ZERO)
			: Blocks.STONE.defaultBlockState().getMapColor(level, BlockPos.ZERO);
		this.scannedColor = color.id;
		this.scannedHeight = 100;
		this.scannedDepth = 0;
	}

	private void scanColumn(final Level level, final LevelChunk chunk, final int x, final int z) {
		BlockPos.MutableBlockPos blockPos = this.pos;
		blockPos.set(x, 0, z);
		int minY = level.getMinY();
		int columnY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
		int waterDepth = 0;
		BlockState state;
		if (columnY <= minY) {
			state = Blocks.BEDROCK.defaultBlockState();
		} else {
			do {
				blockPos.setY(--columnY);
				state = chunk.getBlockState(blockPos);
			} while (state.getMapColor(level, blockPos) == MapColor.NONE && columnY > minY);

			if (columnY > minY && !state.getFluidState().isEmpty()) {
				int solidY = columnY - 1;
				BlockPos.MutableBlockPos below = this.belowPos;
				below.set(blockPos);

				BlockState belowBlock;
				do {
					below.setY(solidY--);
					belowBlock = chunk.getBlockState(below);
					waterDepth++;
				} while (solidY > minY && !belowBlock.getFluidState().isEmpty());

				state = correctStateForFluidBlock(level, state, blockPos);
			}
		}

		this.scannedColor = state.getMapColor(level, blockPos).id;
		this.scannedHeight = columnY;
		this.scannedDepth = waterDepth;
	}

	private static BlockState correctStateForFluidBlock(final Level level, final BlockState state, final BlockPos pos) {
		FluidState fluidState = state.getFluidState();
		return !fluidState.isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP) ? fluidState.createLegacyBlock() : state;
	}
}
