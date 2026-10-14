# LOD Gen

Generates distant terrain for [Voxy](https://modrinth.com/mod/voxy) LODs without creating or saving real chunks.
Minecraft 26.2, Fabric, single player.

Only the generation steps that shape what a distant LOD shows are run (biomes, noise, surface), on a separate
thread pool and outside the vanilla chunk system. Structures, caves, features, the light engine, mob spawning and
saving are skipped; sky light is approximated per column. Chunks that already exist on disk are left to the game,
so LODs of real chunks (with the player's builds) are never overwritten.

## Usage

- `config/lodgen.json`: `radius` (chunks), `threads`, `viewDistanceMargin`
- `/lodgen` shows the progress and speed, `/lodgen pause` / `/lodgen resume`
- Progress is stored per dimension in `<world>/lodgen/`

## Building

Voxy is not published to a maven repository and may not be redistributed, so the build compiles against a local
Voxy jar (`voxy_jar` in `gradle.properties`); it is not bundled.

`./gradlew runServer` starts a benchmark (`-Dlodgen.benchmark=true`): it generates around the world spawn without
a player or Voxy and logs the speed, discarding the results.
