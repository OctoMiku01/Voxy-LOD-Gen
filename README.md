# Voxy LOD Gen

> [!WARNING]
> **AI disclaimer:** The code of this mod was written by AI (Claude, by Anthropic). The author directs what it does
> and tests it in game, but does not review the code itself. It has been tested, but not on every setup or mod
> combination. Back up your worlds, and please report problems as an issue.
>
> This mod is mainly made for private use. You are welcome to try it, but it comes without any promise of support.

Generates distant terrain for [Voxy](https://modrinth.com/mod/voxy) LODs without creating or saving real chunks.
Minecraft 26.3, Fabric.

Instead of generating real chunks far away (slow, and they fill up the save), this mod runs the world generation
steps that shape what an LOD shows on its own thread pool, hands the result to Voxy and throws it away again.
Nothing is loaded, ticked or written to the region files.

What the LODs contain:

- **Terrain** of the dimension's generator, including worldgen mods (tested with Tectonic, Terralith, Biomes O'
  Plenty, Streams Reflowing, Incendium and Nullscape), in the Overworld, the Nether and the End. Superflat works
  too; a mod's own generator is tried, and given up for that dimension if it only fails outside the chunk system
- **Caves and ravines**, so their openings show from afar
- **Features** up to `featureRadius` chunks away: trees, plants, lakes...
- **Structures** within the same distance: villages, towers, ruins..., including those of structure mods; the terrain
  is flattened around them everywhere, like in the real world
- **Light** spread like vanilla's: shadows under trees and overhangs, dark caves, light around lava and glowstone

Trees that overlap differ in places from the real world: which one grows depends on the order chunks are
generated in, which is different every time even in vanilla. LODs with only terrain (beyond `featureRadius`) may
show the neighbouring biome's top block in a strip of a few blocks along biome borders, e.g. grass where the real
world has sand; this keeps their generation fast.

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
with `farPlayers` on). Both need the mod on the server, which then also limits them. With
[Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) installed, a right click on the map adds a waypoint
where it points, with the height of the ground there. The map is adapted from
[3D Maps Voxy Addon](https://github.com/raben12paste/3D-maps-Voxy-Addon) (MIT licensed).

## Installing

- **Single player, and the host when playing with friends** (LAN, Essential...): install it next to Voxy. LODs are
  handed to Voxy directly.
- **Friends who join**: install it next to Voxy too, then they get LODs from the host over the network. Without it
  they simply get none.
- **Dedicated server**: install it on the server, and on every client that should get LODs.

The host and everyone who receives LODs need the same version.

## Settings

With [Mod Menu](https://modrinth.com/mod/modmenu) installed, the settings can be changed in game (Mods → Voxy LOD
Gen → settings); a world you host uses them as soon as you leave the screen. Otherwise edit `config/lodgen.json`,
created on first start:

| Option | Default | |
|---|---|---|
| `radius` | 256 | How far LODs reach, in chunks |
| `featureRadius` | 256 | Up to here LODs get features and structures, further away only terrain |
| `horizonRadius` | 0 | Beyond `radius`, up to here (in chunks), rough LODs show the horizon: only the height and biome of the ground, far cheaper than real LODs, replaced by them when they come. Taken once per world (`lodgen/samples/`); other players with this mod get only these samples and their client builds the LODs. Not under a ceiling (the Nether); in the End only the islands, the void stays empty; 0 turns it off. Experimental |
| `mapRadius` | 1024 | How far from you the 3D map may lead generation to where it looks, in chunks; 0 turns it off |
| `terrainFirst` | true | First terrain quickly up to the radius, then the details follow, closest first |
| `structures` | true | Structures in LODs |
| `sendSavedChunks` | true | Explored chunks become LODs too, read from the save, and are sent again when they change |
| `performance` | `BALANCED` | How much of the CPU generation may use: `VERY_LOW` (1 thread), `LOW` (an eighth of the cores), `BALANCED` (a quarter), `FAST` (half), `FULL` (all but two) or `CUSTOM`; while the single player game is paused at least `FAST` |
| `threads` | a quarter of the cores | Worker threads while playing, with `CUSTOM` |
| `threadsWhilePaused` | half of the cores | Worker threads while the single player game is paused, with `CUSTOM` |
| `tickTimeLimit` | 55 | While the server's ticks take longer than this many milliseconds on average, only one thread generates; 0 turns it off |
| `keepUnderground` | false | Also send what lies deep underground where no daylight reaches (only visible from inside caves) |
| `tileSize` | 16 | Chunks with features are generated in tiles of this size |
| `viewDistanceMargin` | 0 | Chunks this close beyond the view distance are left to the game |
| `sendToPlayers` | true | Send LODs to other players |
| `farPlayers` | true | Show other players beyond the view distance on the LODs, with what they ride |
| `maxChunksPerSecondPerPlayer` | 20 | Network limit per player |
| `maxHorizonChunksPerSecondPerPlayer` | 500 | Network limit per player for the horizon, a few dozen bytes per chunk |
| `checkForUpdates` | true | Ask GitHub at each start whether a newer version exists and say so in chat |
| `updateMessageDelay` | 10 | Seconds after joining a world before the update message shows |

## Commands

- `/lodgen` shows the progress and speed
- `/lodgen pause` / `/lodgen resume`
- `/lodgen reset` sends your LODs of the current dimension again, e.g. after deleting Voxy's data or updating this mod
- `/lodgen compare` compares the LODs around you with the real chunks there (for finding generation differences)
- `/lodgen bench [radius]` (operators only) measures how fast this computer builds LODs with features around you, with
  the threads of the performance setting; the generation pauses meanwhile. Handy for choosing a performance level and
  for bug reports

The F3 screen shows a line with the speed, threads and performance level of the world you host, or how many LODs
came from the server. It can be switched off like every entry there (F3 + F6).

Progress is stored per player and dimension in `<world>/lodgen/players/`.

## Building

The build compiles against Voxy from Modrinth's maven (`voxy_version` in `gradle.properties`, a Modrinth version
id); it is not bundled.

`./gradlew runServer` starts a benchmark (`-Dlodgen.benchmark=true`): it generates around the world spawn without
a player or Voxy and logs the speed, discarding the results.
