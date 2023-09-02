plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.tmvkrpxl0"
version = "1.0"

repositories {
    maven(url = "https://maven.minecraftforge.net")
    maven(url = "https://maven.neoforged.net")
    maven(url = "https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/maven")
    mavenCentral()
}

dependencies {
    implementation(group = "net.neoforged", name = "NeoGradle", version = "6.+")
    implementation("net.minecraftforge:srgutils:0.5.3")
    implementation("commons-io:commons-io:2.11.0")
    implementation(gradleApi())
}

publishing {
    publications {
        create<MavenPublication>("krangemaptasks") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "github"
            setUrl(rootDir.toURI().resolve("maven").toURL())
        }
    }
}