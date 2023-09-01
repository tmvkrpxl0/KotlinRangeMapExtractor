import com.google.common.collect.ImmutableMap
import net.minecraftforge.gradle.common.tasks.JarExec
import net.minecraftforge.srgutils.IMappingFile
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile


buildscript {
    repositories {
        maven(url = "https://maven.minecraftforge.net")
        maven(url = "https://maven.neoforged.net")
        maven(url = "https://raw.githubusercontent.com/tmvkrpxl0/KotlinRangeMapExtractor/main/maven")
        mavenCentral()
    }
    dependencies {
        classpath(group = "net.neoforged", name = "NeoGradle", version = "6.+")
        classpath("net.minecraftforge:srgutils:0.4.13")
        classpath("commons-io:commons-io:2.8.0")
        classpath("com.tmvkrpxl0:krangemap:1.3")
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
            sourceSet.java.srcDirs
        }

        if (sourceDirs.isEmpty()) {
            throw RuntimeException("No java source directories found to update!")
        }

        task<DownloadSrgFiles>("downloadSrgFiles") {
            input.set(updateSrgs)
            reversed.set(isReversed)
        }

        task<ExtractRangeMap>("extractRangeMapNew") {
            sources.from(sourceDirs)
            dependencies.from(sourceSets.map { it.compileClasspath })
        }

        task<ApplyRangeMap>("applyRangeMapNew") {
            println(tasks["extractRangeMapNew"].javaClass)
            rangeMap.set((tasks["extractRangeMapNew"] as ExtractRangeMap).output)
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

abstract class ApplyRangeMap : JarExec() {
    @get:Input
    var annotate = false

    @get:Input
    var keepImports = true

    init {
        tool.set("com.tmvkrpxl0:krangemap:1.+")
        args.addAll(
            "--apply", "--input", "{input}", "--range", "{range}", "--srg", "{srg}", "--exc", "{exc}",
            "--output", "{output}", "--keepImports", "{keepImports}"
        )
        setMinimumRuntimeJavaVersion(8)
        output.convention(project.layout.buildDirectory.dir(name).map { d ->
            d.file(
                "output.zip"
            )
        })
    }

    override fun filterArgs(args: List<String>): List<String> {
        return replaceArgs(
            args, ImmutableMap.of(
                "{range}", rangeMap.get().asFile,
                "{output}", output.get().asFile,
                "{annotate}", annotate,
                "{keepImports}", keepImports
            ), ImmutableMap.of(
                "{input}", sources.files,
                "{srg}", srgFiles.files,
                "{exc}", excFiles.files
            )
        )
    }

    @get:InputFiles
    abstract val srgFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    abstract val excFiles: ConfigurableFileCollection

    @get:InputFile
    abstract val rangeMap: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty
}


abstract class ExtractRangeMap : JarExec() {
    @get:Input
    var batch = true

    init {
        tool.set("com.tmvkrpxl0:krangemap:1.+")
        args.addAll(
            "--extract", "--source-compatibility", "{compat}", "--output", "{output}", "--lib",
            "{library}", "--input", "{input}", "--batch", "{batched}"
        )
        setMinimumRuntimeJavaVersion(8)
        output.convention(project.layout.buildDirectory.dir(name).map { d -> d.file("output.txt") })
        val extension = project.extensions.findByType(JavaPluginExtension::class.java)
        if (extension != null && extension.toolchain.languageVersion.isPresent) {
            val version = extension.toolchain.languageVersion.get().asInt()
            sourceCompatibility.convention((if (version <= 8) "1." else "") + version)
        } else {
            sourceCompatibility.convention("1.8")
        }
    }

    override fun filterArgs(args: List<String>): List<String> {
        return replaceArgs(
            args, ImmutableMap.of(
                "{compat}", sourceCompatibility.get(),
                "{output}", output.get().asFile,
                "{batched}", batch
            ), ImmutableMap.of(
                "{input}", sources.files,
                "{library}", dependencies.files
            )
        )
    }

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    abstract val dependencies: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:Input
    abstract val sourceCompatibility: Property<String?>
}


abstract class ExtractExistingFiles : DefaultTask() {
    @TaskAction
    @Throws(IOException::class)
    fun run() {
        ZipFile(archive.get().asFile).use { zip ->
            val enu: Enumeration<out ZipEntry> = zip.entries()
            while (enu.hasMoreElements()) {
                val e = enu.nextElement()
                if (e.isDirectory) continue
                for (target in targets) {
                    val out = File(target, e.name)
                    if (!out.exists()) continue
                    FileOutputStream(out).use { fos -> IOUtils.copy(zip.getInputStream(e), fos) }
                }
            }
        }
    }

    @get:InputFile
    abstract val archive: RegularFileProperty

    @get:OutputDirectories
    abstract val targets: ConfigurableFileCollection
}
