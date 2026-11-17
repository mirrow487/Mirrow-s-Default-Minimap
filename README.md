# Mirrow's Default Minimap

A client-side Fabric mod for Minecraft Java **26.3** that adds a minimap which looks exactly like a filled vanilla map.

- Vanilla map colours and shading (same logic as `MapItem`: `BlockState#getMapColor`, `MapColor.Brightness`, height shading against the block to the north, water depth, the Nether ceiling pattern).
- North is always up, and the map scrolls as you move.
- Places you have not seen yet (chunks you have never had loaded) are black.
- Explored map data is saved per world/server and per dimension, so it comes back when you restart the game.
- Banner waypoints: right click a banner with an empty hand or with a map in your hand to add it. Right click it again to remove it. Waypoints use the vanilla banner map markers in the banner's colour, and show the banner's custom name. Waypoints outside the minimap are pinned to its edge, pointing the right way. When a banner is broken, the waypoint is removed the next time you come near it.
- A full screen world map in the same style.

Every texture (map paper, player arrow, banner markers) comes from the game's own resources. None of them are copied into this mod.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.3.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `mirrows-default-minimap-<version>.jar` into your `.minecraft/mods` folder.
3. You only need the mod on the client. Servers do not need it.

### Building from source

Requires Java 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`.

## Keys

All keys can be changed under *Options → Controls → Key Binds → Mirrow's Default Minimap*.

| Key | Action |
| --- | --- |
| `M` | Show/hide the minimap |
| `=` / `+` | Zoom in (1:4 → 1:2 → 1:1 blocks per pixel) |
| `-` | Zoom out |
| `J` | Open/close the full screen world map |

On the world map: drag with the left mouse button to pan. Use the scroll wheel or `+`/`-` to zoom (4:1 to 1:16). Close it with `J` or `Esc`.

## Config

`config/mirrows_default_minimap.json` is created on first launch:

```json
{
  "enabled": true,
  "position": "TOP_RIGHT",
  "size": 96,
  "scale": 1,
  "margin": 4,
  "showCoordinates": false
}
```

| Option | Meaning |
| --- | --- |
| `enabled` | Minimap shown (the `M` key changes this too) |
| `position` | `TOP_RIGHT`, `TOP_LEFT`, `BOTTOM_RIGHT` or `BOTTOM_LEFT` |
| `size` | Size of the map area in GUI pixels (32 to 512) |
| `scale` | Blocks per map pixel: `1`, `2` or `4` (the zoom keys change this too) |
| `margin` | Distance from the screen edge in GUI pixels |
| `showCoordinates` | Shows the player's coordinates under the minimap. Off by default, so recordings stay clean |

Map data is stored in `config/mirrows_default_minimap/<world>/<dimension>/`:

- `r.<x>.<z>.mdm` region files, each covering 512×512 blocks
- `waypoints.json`

Delete a folder to reset the map for that world or dimension.
