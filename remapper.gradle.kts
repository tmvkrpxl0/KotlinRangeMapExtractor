import com.tmvkrpxl0.krange.tasks.DownloadSrgFiles
import com.tmvkrpxl0.krange.tasks.ExtractKRangeMap
import com.tmvkrpxl0.krange.tasks.ApplyKRangeMap
import net.minecraftforge.gradle.common.tasks.ExtractExistingFiles

buildscript {
    repositories {
        maven(url = "https://maven.minecraftforge.net")
        maven(url = "https://maven.neoforged.net")
        maven(url = "https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/maven")
        mavenCentral()
    }

    dependencies {
        classpath("com.tmvkrpxl0:krangemap-tasks:1.+")
        classpath(group = "net.neoforged", name = "NeoGradle", version = "6.+")
    }
}

repositories {
    maven(url = "https://maven.minecraftforge.net")
    maven(url = "https://maven.neoforged.net")
    maven(url = "https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/maven")
    mavenCentral()
}

val updateSrgs = (findProperty("UPDATE_SRGS") as? String)?.split(";")
val isReversed = (findProperty("REVERSED") as? String)?.toBoolean() ?: false

updateSrgs?.let { urls ->
    afterEvaluate(Action<Project> {
        val allSourceSets = project.extensions.getByType(SourceSetContainer::class).asMap
        val sourceSets = ((this.project.findProperty("UPDATE_SOURCESETS") as? String) ?: "main")
            .split(";")
            .map { sourceSet ->
                allSourceSets[sourceSet] ?: throw RuntimeException("Sourceset with name $sourceSet was not found")
            }

        val sourceDirs = sourceSets.map { sourceSet ->
            (sourceSet.extensions.findByName("kotlin") as? SourceDirectorySet)?.srcDirs
        }.filterNotNull()

        if (sourceDirs.isEmpty()) {
            throw RuntimeException("No kotlin source directories found to update!")
        }

        task<DownloadSrgFiles>("downloadSrgFiles") {
            input.set(urls)
            reversed.set(isReversed)
        }

        task<ExtractKRangeMap>("extractRangeMapNew") {
            sources.from(sourceDirs)
            dependencies.from(sourceSets.map { it.compileClasspath })
        }

        task<ApplyKRangeMap>("applyRangeMapNew") {
            rangeMap.set((tasks["extractRangeMapNew"] as ExtractKRangeMap).output)
            srgFiles.from((tasks["downloadSrgFiles"] as DownloadSrgFiles).output.asFileTree)
            sources.from(sourceDirs)
        }

        task<ExtractExistingFiles>("extractMappedNew") {
            archive.set((tasks["applyRangeMapNew"] as ApplyKRangeMap).output)
            targets.from(sourceDirs)
        }

        task("updateEverything") {
            dependsOn(tasks["extractMappedNew"])
        }
    })
}

