import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.22"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://maven.minecraftforge.net/")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(kotlin("compiler-embeddable"))
    implementation("net.minecraftforge:Srg2Source:8.0.8")
    implementation("net.minecraftforge:srgutils:0.4.11")
    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

