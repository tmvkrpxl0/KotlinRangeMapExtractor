buildscript {
    repositories {
        maven(url = "https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/maven")
        mavenCentral()
    }

    dependencies {
        classpath("com.tmvkrpxl0:krangemap:1.+:tasks")
    }
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
            sourceSet.java.srcDirs
        }

        if (sourceDirs.isEmpty()) {
            throw RuntimeException("No java source directories found to update!")
        }

        task<DownloadSrgFiles>("downloadSrgFiles") {
            input.set(updateSrgs)
            reversed.set(isReversed)
        }

        task<ExtractKRangeMap>("extractRangeMapNew") {
            sources.from(sourceDirs)
            dependencies.from(sourceSets.map { it.compileClasspath })
        }

        task<ApplyRangeMap>("applyRangeMapNew") {
            println(tasks["extractRangeMapNew"].javaClass)
            rangeMap.set((tasks["extractRangeMapNew"] as ExtractKRangeMap).output)
            srgFiles.from((tasks["downloadSrgFiles"] as DownloadSrgFiles).output.asFileTree)
            sources.from(sourceDirs)
        }

        task<ExtractExistingFiles>("extractMappedNew") {
            archive.set((tasks["applyRangeMapNew"] as ApplyRangeMap).output)
            targets.from(sourceDirs)
        }

        task("updateEverything") {
            dependsOn(tasks["extractMappedNew"])
        }
    })
}

