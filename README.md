# LOD Gen

Generates distant terrain for [Voxy](https://modrinth.com/mod/voxy) LODs without creating or saving real chunks.
Minecraft 26.2, Fabric.

Only the generation steps that shape what a distant LOD shows are run (biomes, noise, surface), on a separate
thread pool and outside the vanilla chunk system. Structures, caves, features, the light engine, mob spawning and
saving are skipped; sky light is approximated per column. Chunks that already exist on disk are left to the game,
so LODs of real chunks (with the player's builds) are never overwritten.

## Who gets LODs

- The player hosting a single player world (also when opened to LAN or shared with friends): handed to Voxy directly.
- Every other player whose client has this mod and Voxy: sent over the network, at most
  `maxChunksPerSecondPerPlayer` chunks per second each (about 20 KiB per chunk before compression).
- Players without the mod get nothing from it. On a dedicated server the mod only has to be on the server for the
  generation, and on the clients that should receive LODs.

A chunk several players need is generated once.

## Usage

- `config/lodgen.json`: `radius` (chunks), `threads`, `viewDistanceMargin`, `sendToPlayers`,
  `maxChunksPerSecondPerPlayer`
- `/lodgen` shows the progress and speed, `/lodgen pause` / `/lodgen resume`
- `/lodgen reset` sends your LODs of the current dimension again (e.g. after deleting Voxy's data)
- Progress is stored per player and dimension in `<world>/lodgen/players/`

## Building

Voxy is not published to a maven repository and may not be redistributed, so the build compiles against a local
Voxy jar (`voxy_jar` in `gradle.properties`); it is not bundled.

`./gradlew runServer` starts a benchmark (`-Dlodgen.benchmark=true`): it generates around the world spawn without
a player or Voxy and logs the speed, discarding the results.
