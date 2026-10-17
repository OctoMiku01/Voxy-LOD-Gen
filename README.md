# Voxy LOD Gen

> [!WARNING]
> **AI disclaimer:** The code of this mod was written by AI (Claude, by Anthropic). The author directs what it does
> and tests it in game, but does not review the code itself. It has been tested, but not on every setup or mod
> combination. Back up your worlds, and please report problems as an issue.
>
> This mod is mainly made for private use. You are welcome to try it, but it comes without any promise of support.

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

Chunks that already exist in the world (explored, with everyone's builds) are never generated: their LODs are read
straight from the save, without loading them, and sent again whenever they are saved with changes. Players see what
others explored and built far away, like with Voxy Server Side. Areas that only got terrain are sent again with
features once a player comes close enough.

Other players beyond the view distance are shown on the LODs too, like with Voxy Server Side: with their skin,
armor and what they hold, on what they ride (horses, boats...), lit as it is where they are, and with their name
tags, which stay readable from far away but are hidden behind terrain. Up close the game shows the real ones again.

With Voxy installed, `M` opens a 3D map: the camera rises high above you and shows the LODs from there, as far as
they reach. Drag to move, wheel to zoom, middle mouse button to turn, `C` to come back to you. Fog, clouds, rain and
the HUD are hidden while it is open, and the time of day can be chosen on it. In the Nether it looks straight down
and cuts the world open below the roof, at a height set with a slider (or Page Up/Down) to look through it layer by
layer. With a shader pack on, the map shows
the LODs alone in front of a plain background, as packs draw clouds and haze of their own. While the map is open, the
terrain around where it looks is generated first, also beyond `radius` (up to `mapRadius` from you): move it somewhere
unexplored and watch the land appear. Every player in the dimension shows on it with their face and name (others only
with `farPlayers` on). Both need the mod on the server, which then also limits them. The map is adapted from
[3D Maps Voxy Addon](https://github.com/raben12paste/3D-maps-Voxy-Addon) (MIT licensed).

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
| `mapRadius` | 1024 | How far from you the 3D map may lead generation to where it looks, in chunks; 0 turns it off |
| `terrainFirst` | true | First terrain quickly up to the radius, then the details follow, closest first |
| `structures` | true | Structures in LODs |
| `sendSavedChunks` | true | Explored chunks become LODs too, read from the save, and are sent again when they change |
| `threads` | a quarter of the cores | Worker threads while playing |
| `threadsWhilePaused` | half of the cores | Worker threads while the single player game is paused |
| `keepUnderground` | false | Also send what lies deep underground where no daylight reaches (only visible from inside caves) |
| `tileSize` | 16 | Chunks with features are generated in tiles of this size |
| `viewDistanceMargin` | 0 | Chunks this close beyond the view distance are left to the game |
| `sendToPlayers` | true | Send LODs to other players |
| `farPlayers` | true | Show other players beyond the view distance on the LODs, with what they ride |
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
