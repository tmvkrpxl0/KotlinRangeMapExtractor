package com.tmvkrpxl0.krange.tasks

import net.minecraftforge.srgutils.IMappingFile
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URL

abstract class DownloadSrgFiles : DefaultTask() {
    @get:Input
    abstract val input: SetProperty<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val reversed: Property<Boolean>

    init {
        output.convention(project.layout.buildDirectory.dir(name))

        // URLs can change their contents over time
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun doTask() {
        val reversed = this.reversed.getOrElse(false)
        val outputDir = this.output.get()
        if (outputDir.asFile.exists())
            FileUtils.deleteDirectory(outputDir.asFile)

        val usedFilenames = HashMap<String, Int>()
        input.get().forEach { urlStr ->
            val url = URL(urlStr)
            val srg = url.openConnection().inputStream.use { IMappingFile.load(it) }
            var fileName = url.path.substring(url.path.lastIndexOf('/') + 1)
            val dotIdx = fileName.lastIndexOf('.')
            if (dotIdx != -1) {
                fileName = fileName.substring(0, dotIdx)
            }
            val uniqueIdx = usedFilenames.compute(fileName) { _, v -> v?.plus(1) ?: 0 }
            val outputSrgFile = outputDir.file(fileName + if (uniqueIdx == 0) "" else "${uniqueIdx}.tsrg").asFile
            srg.write(outputSrgFile.toPath(), IMappingFile.Format.TSRG2, reversed)
        }
    }
}