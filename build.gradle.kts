import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.0"
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "7.1.2" // Add Shadow Plugin
}

group = "com.tmvkrpxl0"
version = "1.4"

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

tasks {
    jar {
        manifest {
            attributes("Main-Class" to "com.tmvkrpxl0.krange.MainKt")
        }
    }

    test {
        useJUnitPlatform()
    }

    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    create<Jar>("sourceJar") {
        from(sourceSets.main.get().allSource)
        archiveClassifier.set("sources")
    }

    shadowJar {
        archiveClassifier.set("fatJar")
    }
}

publishing {
    repositories {
        maven {
            name = "github"
            setUrl("./maven")
        }
    }
    publications {
        create<MavenPublication>("github") {
            from(components["java"])
            artifactId = rootProject.name

            artifacts {
                archives(tasks["sourceJar"]!!)
                archives(tasks.shadowJar)
            }
        }
    }
}
