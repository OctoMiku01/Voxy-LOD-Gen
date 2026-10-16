// Minecraft 26.x ships unobfuscated, so this uses the non-remapping Fabric Loom.
plugins {
    id("net.fabricmc.fabric-loom") version "1.18.3"
}

val minecraft = property("minecraft_version") as String

version = "${property("mod_version")}+$minecraft"
group = "io.github.octomiku01"

base {
    archivesName = "voxy-lodgen"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

repositories {
    maven("https://maven.terraformersmc.com/releases") { content { includeGroup("com.terraformersmc") } }
    maven("https://maven.shedaniel.me") { content { includeGroup("me.shedaniel.cloth") } }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    compileOnly(files(property("voxy_jar")))
    // Optional: the settings screen shows up in Mod Menu when both are installed
    compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}") { isTransitive = false }
    compileOnly("me.shedaniel.cloth:cloth-config-fabric:${property("cloth_config_version")}") { isTransitive = false }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "minecraft_version" to minecraft,
        "loader_version" to project.property("loader_version"),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

loom {
    runs {
        named("server") {
            // Measure generation speed on the dev server: no Voxy, no player, results are discarded
            vmArg("-Dlodgen.benchmark=true")
        }
        register("mpServer") {
            server()
            runDir("run-mp/server")
            vmArgs("-Dlodgen.mptest=true", "-Xmx4G")
            programArgs("nogui")
        }
        for (name in listOf("A", "B")) {
            register("mpClient$name") {
                client()
                runDir("run-mp/client$name")
                vmArgs("-Dlodgen.mptest=true", "-Xmx4G")
                programArgs("--username", "Player$name", "--quickPlayMultiplayer", "localhost:25565")
            }
        }
    }
}
