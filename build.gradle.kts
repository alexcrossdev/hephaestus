import java.net.URI
import java.util.zip.ZipFile
import java.nio.file.Files
import org.gradle.api.GradleException

plugins {
    java
}

group = "dev.hephaestus"
version = property("mc_version").toString()

// ── Directories ───────────────────────────────────────────────────────────────

val cacheDir        = layout.projectDirectory.dir(".hephaestus")
val serverJar       = cacheDir.file("server.jar")
val vineflowerJar   = cacheDir.file("vineflower.jar")
val innerServerJar  = cacheDir.file("server-real-${version}.jar")
val libsDir         = cacheDir.dir("libs")
val sourcesDir      = cacheDir.dir("sources")

// ── Java toolchain ────────────────────────────────────────────────────────────

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// ── Dependencies ──────────────────────────────────────────────────────────────

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files(innerServerJar))
    compileOnly(fileTree(libsDir) { include("**/*.jar") })
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("com.google.errorprone:error_prone_annotations:2.26.1")
}

tasks.compileJava {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--enable-preview"))
    dependsOn("extractServer")
}

// ── Task: Download server.jar ─────────────────────────────────────────────────

tasks.register("downloadServer") {
    group = "hephaestus"
    description = "Downloads the Minecraft ${version} server jar from Mojang."

    outputs.file(serverJar)

    doLast {
        if (serverJar.asFile.exists()) {
            println("  server.jar already downloaded, skipping.")
            return@doLast
        }
        cacheDir.asFile.mkdirs()
        val url = providers.gradleProperty("server_jar_url").get()
        println("  Downloading server.jar from Mojang...")
        URI(url).toURL().openStream().use { input ->
            serverJar.asFile.outputStream().use { output -> input.copyTo(output) }
        }
        println("  Downloaded server.jar (${serverJar.asFile.length() / 1024}KB)")
    }
}

// ── Task: Download Vineflower ─────────────────────────────────────────────────

tasks.register("downloadVineflower") {
    group = "hephaestus"
    description = "Downloads the Vineflower decompiler."

    outputs.file(vineflowerJar)

    doLast {
        if (vineflowerJar.asFile.exists()) {
            println("  vineflower.jar already downloaded, skipping.")
            return@doLast
        }
        val url = providers.gradleProperty("vineflower_url").get()
        println("  Downloading Vineflower...")
        URI(url).toURL().openStream().use { input ->
            vineflowerJar.asFile.outputStream().use { output -> input.copyTo(output) }
        }
        println("  Downloaded vineflower.jar")
    }
}

// ── Task: Extract inner server jar + libraries ────────────────────────────────

tasks.register("extractServer") {
    group = "hephaestus"
    description = "Extracts the inner server jar and bundled libraries from the Mojang bundler."

    dependsOn("downloadServer")
    inputs.file(serverJar)
    outputs.file(innerServerJar)
    outputs.dir(libsDir)

    doLast {
        val bundler = ZipFile(serverJar.asFile)

        // Extract inner server jar
        if (!innerServerJar.asFile.exists()) {
            val entry = bundler.getEntry("META-INF/versions/${version}/server-${version}.jar")
                ?: error("Could not find inner server jar in bundler")
            innerServerJar.asFile.parentFile.mkdirs()
            bundler.getInputStream(entry).use { input ->
                innerServerJar.asFile.outputStream().use { output -> input.copyTo(output) }
            }
            println("  Extracted server-real-${version}.jar (${innerServerJar.asFile.length() / 1024 / 1024}MB)")
        } else {
            println("  server-real-${version}.jar already extracted, skipping.")
        }

        // Extract bundled libraries
        libsDir.asFile.mkdirs()
        var libCount = 0
        bundler.entries().asSequence()
            .filter { it.name.startsWith("META-INF/libraries/") && it.name.endsWith(".jar") }
            .forEach { entry ->
                val libName = entry.name.substringAfterLast("/")
                val dest = libsDir.file(libName).asFile
                if (!dest.exists()) {
                    bundler.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    libCount++
                }
            }

        bundler.close()
        if (libCount > 0) println("  Extracted $libCount library jars")
        else println("  Libraries already extracted, skipping.")
    }
}

// ── Task: Decompile sources ───────────────────────────────────────────────────

tasks.register<JavaExec>("decompile") {
    group = "hephaestus"
    description = "Decompiles the server jar into readable Java sources using Vineflower."

    dependsOn("extractServer", "downloadVineflower")

    classpath = files(vineflowerJar)
    mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")

    doFirst {
        if (sourcesDir.asFile.exists() && sourcesDir.asFile.listFiles()?.isNotEmpty() == true) {
            println("  Sources already decompiled, skipping.")
            println("  Delete .hephaestus/sources/ to force a re-decompile.")
            throw StopExecutionException()
        }
        sourcesDir.asFile.mkdirs()
        println("  Decompiling server sources (this may take a few minutes)...")
        args(
            innerServerJar.asFile.absolutePath,
            sourcesDir.asFile.absolutePath
        )
    }

    doLast {
        println("  Decompiled to .hephaestus/sources/")
    }
}

// ── Task: Patch helper ────────────────────────────────────────────────────────

tasks.register("patch") {
    group = "hephaestus"
    description = """
        Copies a class from decompiled sources into src/main/java for patching.

        Usage:
          ./gradlew patch -Pclass=net/minecraft/server/MinecraftServer
    """.trimIndent()

    dependsOn("decompile")

    doLast {
        val className = project.providers
            .gradleProperty("class")
            .orNull
            ?: throw GradleException(
                "Provide a class path with -Pclass=..., e.g. -Pclass=net/minecraft/server/MinecraftServer"
            )

        val javaPath =
            if (className.endsWith(".java")) className
            else "$className.java"

        val src = layout.projectDirectory
            .file(".hephaestus/sources/$javaPath")
            .asFile
        val dst = layout.projectDirectory
            .file("src/main/java/$javaPath")
            .asFile
        if (!src.exists()) {
            throw GradleException(
                "Source not found: ${src.absolutePath}\n" +
                "Run './gradlew decompile' first."
            )
        }

        if (dst.exists()) {
            logger.lifecycle("Already patched: src/main/java/$javaPath")
            logger.lifecycle("Delete the file to re-copy from sources.")
            return@doLast
        }

        dst.parentFile.mkdirs()
        src.copyTo(dst, overwrite = false)

        logger.lifecycle("✓ Copied to src/main/java/$javaPath")
        logger.lifecycle("Edit the file, then run: ./gradlew buildServer")
    }
}

// ── Task: Create Patch ────────────────────────────────────────────────────────

tasks.register<Exec>("createPatch") {
    group = "hephaestus"

    val className = project.providers.gradleProperty("class")

    doFirst {
        val path = className.orNull
            ?: throw GradleException("Missing -Pclass")

        val javaPath =
            if (path.endsWith(".java")) path else "$path.java"

        val original = file(".hephaestus/sources/$javaPath")
        val modified = file("src/main/java/$javaPath")
        val patch = file("patches/$javaPath.patch")

        patch.parentFile.mkdirs()

        commandLine(
            "diff",
            "-u",
            original.absolutePath,
            modified.absolutePath
        )

        standardOutput = patch.outputStream()

        // diff returns 1 when differences exist
        isIgnoreExitValue = true
    }

    doLast {
        when (executionResult.get().exitValue) {
            0 -> logger.lifecycle("No changes found.")
            1 -> logger.lifecycle("Patch created successfully.")
            else -> throw GradleException(
                "diff failed with exit code ${executionResult.get().exitValue}"
            )
        }
    }
}

// ── Task: Apply Patchs ────────────────────────────────────────────────────────

tasks.register("applyPatches") {
    group = "hephaestus"
    description = "Copies patched classes into src/main/java and applies all patches"

    dependsOn("decompile")

    doLast {
        val patchesDir = file("patches")

        if (!patchesDir.exists()) {
            logger.lifecycle("No patches directory found.")
            return@doLast
        }

        fileTree(patchesDir) {
            include("**/*.patch")
        }.files.forEach { patchFile ->

            val relativePatch = patchFile
                .relativeTo(patchesDir)
                .invariantSeparatorsPath

            val javaPath = relativePatch.removeSuffix(".patch")

            val sourceFile = file(".hephaestus/sources/$javaPath")
            val targetFile = file("src/main/java/$javaPath")

            if (!sourceFile.exists()) {
                throw GradleException(
                    "Source file not found for patch: $javaPath\n" +
                    "Expected: ${sourceFile.absolutePath}"
                )
            }

            targetFile.parentFile.mkdirs()

            sourceFile.copyTo(
                targetFile,
                overwrite = true
            )

            logger.lifecycle("Applying $relativePatch")

            val exitCode = ProcessBuilder(
                "patch",
                targetFile.absolutePath,
                patchFile.absolutePath
            )
                .directory(projectDir)
                .inheritIO()
                .start()
                .waitFor()

            if (exitCode != 0) {
                throw GradleException(
                    "Failed to apply patch: $relativePatch"
                )
            }

            logger.lifecycle("✓ Applied $relativePatch")
        }
    }
}

// ── Task: List patched classes ────────────────────────────────────────────────

tasks.register("patches") {
    group = "hephaestus"
    description = "Lists all currently patched classes in src/main/java."

    doLast {
        val srcDir = layout.projectDirectory.dir("src/main/java").asFile
        if (!srcDir.exists() || srcDir.walkTopDown().filter { it.extension == "java" }.none()) {
            println("  No patched classes yet.")
            println("  Use: ./gradlew patch -Pclass=net/minecraft/server/MinecraftServer")
        } else {
            println("  Patched classes:")
            srcDir.walkTopDown()
                .filter { it.extension == "java" }
                .forEach { println("    - ${it.relativeTo(srcDir)}") }
        }
    }
}

// ── Task: Build patched inner jar ─────────────────────────────────────────────

val buildPatchedInnerJar by tasks.registering(Jar::class) {
    group = "hephaestus"
    dependsOn(tasks.compileJava, "extractServer")
    archiveFileName.set("server-${version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("patched-inner"))

    // Patched classes first (take priority over vanilla)
    from(tasks.compileJava.get().destinationDirectory)
    // Vanilla classes fill the rest
    from(zipTree(innerServerJar))

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

// ── Task: Build final server jar ──────────────────────────────────────────────

tasks.register<Jar>("buildServer") {
    group = "hephaestus"
    description = "Assembles the final patched server jar."

    dependsOn(buildPatchedInnerJar)
    archiveFileName.set("hephaestus-${version}.jar")

    // Bundler bootstrap — strip original inner jar and signatures
    from(zipTree(serverJar)) {
        exclude("META-INF/versions/${version}/server-${version}.jar")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    }

    // Inject our patched inner jar in its place
    into("META-INF/versions/${version}/") {
        from(buildPatchedInnerJar.get().archiveFile)
    }

    manifest {
        attributes("Main-Class" to "net.minecraft.bundler.Main")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    doLast {
        println("")
        println("  ✓ Built: build/libs/hephaestus-${version}.jar")
        println("")
        println("  Run with:")
        println("  java -Xmx4G -Xms2G --enable-preview -jar build/libs/hephaestus-${version}.jar nogui")
    }
}

tasks.build {
    dependsOn("buildServer")
}

// ── Task: Setup (convenience — runs everything) ───────────────────────────────

tasks.register("setup") {
    group = "hephaestus"
    description = "Downloads, extracts, and decompiles the server. Run this first."
    dependsOn("applyPatches")

    doLast {
        println("")
        println("  ✓ Hephaestus setup complete!")
        println("")
        println("  Next steps:")
        println("  1. Patch a class:  ./gradlew patch -Pclass=net/minecraft/server/MinecraftServer")
        println("  2. Edit the file in src/main/java/")
        println("  3. Build:          ./gradlew buildServer")
        println("  4. Run:            java -Xmx4G -Xms2G --enable-preview -jar build/libs/hephaestus-${version}.jar nogui")
    }
}

// ── Task: Run Server ────────────────────────────────────────────────────────

tasks.register<JavaExec>("runServer") {
    group = "hephaestus"
    description = "Builds and runs the patched Minecraft server inside run."

    dependsOn("buildServer")

    val runDir    = layout.projectDirectory.dir("run").asFile
    val runJar    = File(runDir, "hephaestus-${version}.jar")
    val eulaFile  = File(runDir, "eula.txt")
    val builtJar  = layout.buildDirectory.file("libs/hephaestus-${version}.jar")

    doFirst {
        runDir.mkdirs()
        builtJar.get().asFile.copyTo(runJar, overwrite = true)
        if (!eulaFile.exists()) {
            eulaFile.writeText("eula=true\n")
            println("  Created run/eula.txt")
        }
        println("  Starting Hephaestus server (${version}) in run/...")
    }

    workingDir = runDir
    classpath  = files(runJar)
    mainClass.set("net.minecraft.bundler.Main")
    jvmArgs = listOf("-Xmx4G", "-Xms2G", "--enable-preview")
    args    = listOf("nogui")
    standardInput = System.`in`
}
