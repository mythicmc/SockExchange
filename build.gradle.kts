import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.2.2"
    id("net.kyori.blossom") version "2.2.0"
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.3" // IntelliJ + Blossom integration
}

group = "com.gmail.tracebachi"
version = "1.1.2"

repositories {
    mavenCentral()
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
    maven(url = "https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven(url = "https://nexus.velocitypowered.com/repository/maven-public/")
}

dependencies {
    compileOnly("io.netty:netty-all:4.0.23.Final")
    compileOnly("net.md-5:bungeecord-api:1.21-R0.3")
    compileOnly("org.spigotmc:spigot-api:1.13.2-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.0.1")
    annotationProcessor("com.velocitypowered:velocity-api:3.0.1")
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

sourceSets {
    main {
        blossom {
            resources {
                property("version", project.version.toString())
                property("description", project.description ?: "")
            }
            javaSources {
                property("version", project.version.toString())
                property("description", project.description ?: "")
            }
        }
    }
}

tasks.getByName<ShadowJar>("shadowJar") {
    minimize()
}

publishing {
    repositories {
        maven {
            name = "mythicmcReleases"
            url = uri("https://maven.mythicmc.org/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.gmail.tracebachi"
            artifactId = "sockexchange"
            version = project.version.toString()
            from(components["java"])
            pom {
                name = project.name
                description = project.description
                url = "https://github.com/mythicmc/SockExchange"
                // properties = mapOf("myProp" to "value", "prop.with.dots" to "anotherValue")
                licenses {
                    license {
                        name = "GPL-3.0-only"
                        url = "https://spdx.org/licenses/GPL-3.0-only.html"
                    }
                }
                developers {
                    developer {
                        id = "GeeItsZee"
                        email = "tracebachi@gmail.com"
                    }
                    developer {
                        id = "retrixe"
                        name = "Ibrahim Ansari"
                        email = "ibu2@mythicmc.org"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/mythicmc/SockExchange.git"
                    developerConnection = "scm:git:ssh://github.com/mythicmc/SockExchange.git"
                    url = "https://github.com/mythicmc/SockExchange/"
                }
            }
        }
    }
}
