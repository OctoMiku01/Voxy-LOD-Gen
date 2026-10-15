# Voxy LOD Gen

Generates distant terrain for [Voxy](https://modrinth.com/mod/voxy) LODs without creating or saving real chunks.
Minecraft 26.2, Fabric.

Instead of generating real chunks far away (slow, and they fill up the save), this mod runs the world generation
steps that shape what an LOD shows on its own thread pool, hands the result to Voxy and throws it away again.
Nothing is loaded, ticked or written to the region files.

What the LODs contain:

- **Terrain** of the dimension's generator, including worldgen mods (tested with Tectonic, Terralith, Biomes O'
  Plenty, Streams Reflowing, Incendium and Nullscape), in the Overworld, the Nether and the End
- **Caves and ravines**, so their openings show from afar
- **Features** up to `featureRadius` chunks away: trees, plants, lakes...
- **Structures** within the same distance: villages, towers, ruins..., including those of structure mods; the terrain
  is flattened around them everywhere, like in the real world
- **Light** spread like vanilla's: shadows under trees and overhangs, dark caves, light around lava and glowstone

Trees that overlap differ in places from the real world: which one grows depends on the order chunks are
generated in, which is different every time even in vanilla.

Chunks that already exist in the world are left to the game, so LODs of real chunks (with your builds) are never
overwritten. Areas that only got terrain are sent again with features once a player comes close enough.

## Installing

- **Single player, and the host when playing with friends** (LAN, Essential...): install it next to Voxy. LODs are
  handed to Voxy directly.
- **Friends who join**: install it next to Voxy too, then they get LODs from the host over the network. Without it
  they simply get none.
- **Dedicated server**: install it on the server, and on every client that should get LODs.

The host and everyone who receives LODs need the same version.

## Settings

With [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config)
installed, the settings can be changed in game (Mods → Voxy LOD Gen → settings); a world you host uses them as soon
as you save. Otherwise edit `config/lodgen.json`, created on first start:

| Option | Default | |
|---|---|---|
| `radius` | 256 | How far LODs reach, in chunks |
| `featureRadius` | 128 | Up to here LODs get features and structures, further away only terrain |
| `structures` | true | Structures in LODs |
| `threads` | a quarter of the cores | Worker threads while playing |
| `threadsWhilePaused` | half of the cores | Worker threads while the single player game is paused |
| `keepUnderground` | false | Also send what lies deep underground where no daylight reaches (only visible from inside caves) |
| `tileSize` | 16 | Chunks with features are generated in tiles of this size |
| `viewDistanceMargin` | 0 | Chunks this close beyond the view distance are left to the game |
| `sendToPlayers` | true | Send LODs to other players |
| `maxChunksPerSecondPerPlayer` | 20 | Network limit per player |

## Commands

- `/lodgen` shows the progress and speed
- `/lodgen pause` / `/lodgen resume`
- `/lodgen reset` sends your LODs of the current dimension again, e.g. after deleting Voxy's data or updating this mod
- `/lodgen compare` compares the LODs around you with the real chunks there (for finding generation differences)

Progress is stored per player and dimension in `<world>/lodgen/players/`.

## Building

Voxy is not published to a maven repository and may not be redistributed, so the build compiles against a local
Voxy jar (`voxy_jar` in `gradle.properties`); it is not bundled.

`./gradlew runServer` starts a benchmark (`-Dlodgen.benchmark=true`): it generates around the world spawn without
a player or Voxy and logs the speed, discarding the results.
