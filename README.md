# Hephaestus

A Gradle-based pipeline for decompiling, patching, and recompiling the Minecraft Java Edition server. Downloads the official Mojang server jar, extracts and decompiles the inner server with Vineflower, and reassembles a patched jar with your modifications overlaid on the vanilla classes.

## Requirements

- Java 25+
- Gradle 9.4.0+

## Quick Start

```bash
# 1. Download, extract, and decompile the server
./gradlew setup

# 2. Copy a class you want to patch into src/main/java
./gradlew patch -Pclass=net/minecraft/server/MinecraftServer

# 3. Edit it
nvim src/main/java/net/minecraft/server/MinecraftServer.java

# 4. Build the patched server jar
./gradlew buildServer

# 5. Run it
./gradlew runServer

# 6. Patch it
./gradlew createPatch -Pclass=net/minecraft/server/MinecraftServer
```

## Tasks

| Task | Description |
|---|---|
| `setup` | Downloads, extracts, decompiles, and patches everything. Run this first. |
| `downloadServer` | Downloads the Mojang server jar. |
| `downloadVineflower` | Downloads the Vineflower decompiler. |
| `extractServer` | Extracts the inner server jar and bundled libraries. |
| `decompile` | Decompiles the server into readable Java sources. |
| `patch -Pclass=<path>` | Copies a class from decompiled sources into `src/main/java` for editing. |
| `createPatch -Pclass=<path>` | Create patch file in `patchs` for saving state. |
| `applyPatches` | Applies all patches in `patches` to the decompiled source code. |
| `patches` | Lists all currently patched classes. |
| `buildServer` | Compiles your patches and assembles the final server jar. |
| `runServer` | Builds and runs the patched Minecraft server inside run. |

## How it works

The Mojang server jar is a bundler — it contains a real inner server jar at `META-INF/versions/26.1.2/server-26.1.2.jar` which is what actually runs. Hephaestus extracts that inner jar, decompiles it with Vineflower, lets you patch individual classes, then repackages everything with your compiled classes taking priority over the originals.

Only files in `src/main/java/` are compiled and patched. Everything else runs from the original vanilla classes.

## Changing the Minecraft version

Update `gradle.properties`:

```properties
mc_version=26.1.2
server_jar_url=https://piston-data.mojang.com/...
```

Delete `.hephaestus/` and re-run `./gradlew setup`.
