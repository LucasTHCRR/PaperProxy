import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(project(":velocity-proxy"))
    compileOnly(project(":velocity-api"))
    compileOnly(libs.log4j.api)
    compileOnly(libs.netty.codec)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.asm)

    // The original BungeeCord API. Bungee plugins compile against exactly these classes.
    // Netty, Gson, Guava, SnakeYAML, fastutil, ASM and Brigadier are provided by the proxy.
    implementation("net.md-5:bungeecord-api:1.21-R0.4") {
        exclude(group = "com.mojang", module = "brigadier")
        exclude(group = "io.netty")
        exclude(group = "com.google.code.gson")
        exclude(group = "com.google.guava")
        exclude(group = "org.yaml")
        exclude(group = "it.unimi.dsi")
        exclude(group = "org.ow2.asm", module = "asm")
    }
    // BungeeCord ships the MySQL driver, and plugins such as Party and Friends rely on it.
    implementation("com.mysql:mysql-connector-j:9.4.0") {
        exclude(group = "com.google.protobuf")
    }
    // Needed by Bungee's LibraryLoader for the "libraries" section of bungee.yml.
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")

    testImplementation(project(":velocity-proxy"))
    testImplementation(project(":velocity-api"))
    testImplementation(libs.log4j.api)
}

tasks {
    withType<Checkstyle> {
        // Patched BungeeCord class, kept in BungeeCord's own code style.
        exclude("**/net/md_5/**")
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("paperproxy-bungee-layer")
        // Our patched PluginClassloader must win over the one in bungeecord-api.
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

// Thin variant for the small PaperProxy jar: only the layer's own classes; the BungeeCord API and
// its libraries are listed with their SHA-256 and downloaded when a BungeeCord plugin is present.
val thinJar = tasks.register<Jar>("thinJar") {
    archiveBaseName.set("paperproxy-bungee-layer")
    archiveClassifier.set("thin")
    from(sourceSets.main.get().output)
}

val libraryList = tasks.register("libraryList") {
    val output = layout.buildDirectory.file("generated/paperproxy/bungee-libraries.list")
    val runtime = configurations.runtimeClasspath
    inputs.files(runtime)
    outputs.file(output)
    doLast {
        val lines = mutableListOf("# group:artifact:version:classifier:extension sha256")
        runtime.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
            // The patched PluginClassloader must come first; bungeecord-api is listed after it.
            .forEach { artifact ->
                val id = artifact.moduleVersion.id
                val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                lines += "${id.group}:${id.name}:${id.version}:${artifact.classifier ?: ""}:" +
                    "${artifact.extension} $digest"
            }
        output.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

