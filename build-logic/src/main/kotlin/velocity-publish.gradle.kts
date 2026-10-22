plugins {
    java
    `maven-publish`
}

// PaperProxy publishes its own artifacts to the Codeberg package registry, never to PaperMC.
// Set CODEBERG_TOKEN (a token with package write access) to publish.
extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "codeberg"
            setUrl("https://codeberg.org/api/packages/LucasTHCR/maven")
            credentials(HttpHeaderCredentials::class.java) {
                name = "Authorization"
                value = "token " + (System.getenv("CODEBERG_TOKEN") ?: "")
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "net.paperstream"
            artifactId = "paperproxy-" + project.name.removePrefix("velocity-")
            pom {
                name.set("PaperProxy " + project.name.removePrefix("velocity-").replaceFirstChar { it.uppercase() })
                description.set("API for plugins on PaperProxy, a Velocity fork that also runs BungeeCord plugins. Not affiliated with PaperMC.")
                url.set("https://github.com/LucasTHCRR/PaperProxy")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("LucasTHCR")
                        name.set("LucasTHCR")
                    }
                    developer {
                        name.set("Velocity Contributors")
                        url.set("https://github.com/PaperMC/Velocity")
                    }
                }
                scm {
                    url.set("https://github.com/LucasTHCRR/PaperProxy")
                    connection.set("scm:git:https://github.com/LucasTHCRR/PaperProxy.git")
                }
            }
        }
    }
}
