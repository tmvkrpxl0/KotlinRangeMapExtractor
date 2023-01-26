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
    implementation("com.github.cretz.kastree:kastree-ast-jvm:0.4.0")
    implementation("com.github.cretz.kastree:kastree-ast-psi:0.4.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}