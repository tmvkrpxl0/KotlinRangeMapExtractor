package com.tmvkrpxl0.krange

import com.tmvkrpxl0.krange.Tasks.APPLY
import com.tmvkrpxl0.krange.Tasks.EXTRACT
import joptsimple.OptionException
import joptsimple.OptionParser
import joptsimple.OptionSpec
import joptsimple.ValueConverter
import joptsimple.util.PathConverter
import net.minecraftforge.srg2source.api.InputSupplier
import net.minecraftforge.srg2source.api.OutputSupplier
import net.minecraftforge.srg2source.api.SourceVersion
import net.minecraftforge.srg2source.util.io.FolderSupplier
import net.minecraftforge.srg2source.util.io.ZipInputSupplier
import net.minecraftforge.srg2source.util.io.ZipOutputSupplier
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.notExists

fun main(args: Array<String>) {
    val taskString = Tasks.values().map { "--" + it.name.lowercase() }

    val task = args
        .filter { taskString.contains(it) }
        .let {
            if (it.size > 1) {
                throw IllegalArgumentException("Only one task allowed at a time, trued to run " + it[1] + " when " + it[0] + " already set")
            }
            if (it.isEmpty()) {
                throw IllegalArgumentException("Must specify a task to run: " + taskString.joinToString(", "))
            }
            if (it[0] == "--apply") APPLY else EXTRACT
        }

    val newArgs = args.filterNot { taskString.contains(it) }.toTypedArray()
    task.run(newArgs)
}

private enum class Tasks {
    APPLY {
        val converter = PathConverter()

        override fun run(args: Array<String>) {
            val parser = OptionParser()
            val helpArg = parser.acceptsAll(listOf("h", "help")).forHelp()

            val inputArg = parser.acceptsAll(listOf("in", "input", "srcRoot"))
                .withRequiredArg()
                .withValuesConvertedBy(converter)
                .required()

            val outArg = parser.acceptsAll(listOf("out", "output", "outDir"))
                .withRequiredArg()
                .withValuesConvertedBy(converter)
                .required()

            val excArg = parser.acceptsAll(listOf("exc", "excFiles"))
                .withRequiredArg()
                .withValuesConvertedBy(converter)

            val mappingArg = parser.acceptsAll(listOf("map", "srg", "srgFiles"))
                    .withRequiredArg()
                    .withValuesConvertedBy(converter)
                    .required()

            //TODO: Encoding arguments
            val rangeArg = parser.acceptsAll(listOf("rm", "range", "srcRangeMap"))
                .withRequiredArg()
                .ofType(File::class.java)
                .required()

            val keepImportsArg = parser.acceptsAll(listOf("keepImports"))
                .withOptionalArg()
                .ofType(Boolean::class.java)
                .defaultsTo(true)

            val sortImportArg = parser.acceptsAll(listOf("sortImports"))
            val guessLambdasArg = parser.acceptsAll(listOf("guessLambdas"))
            val guessLocalsArg = parser.acceptsAll(listOf("guessLocals"))

            try {
                val options = parser.parse(*args)
                if (options.has(helpArg)) {
                    parser.printHelpOn(System.out)
                    return
                }

                val range = options.valueOf(rangeArg)
                val output = options.valueOf(outArg)
                val keepImports = options.valueOf(keepImportsArg)
                val inputs = options.valuesOf(inputArg)
                val srgs = options.valuesOf(mappingArg)
                val exceptors = options.valuesOf(excArg)
                val sortImports = options.has(sortImportArg)
                val guessLambda = options.has(guessLambdasArg)
                val guessLocal = options.has(guessLocalsArg)

                println("Range:   $range")
                println("Output:  $output")
                println("Imports: $keepImports")
                println("Sort:    $sortImports")
                println("Lambdas: $guessLambda")
                println("Locals:  $guessLocal")

                buildApplier(
                    inputs = inputs.map { it.input() },
                    output = output.output(),
                    rangeMapPath = range.toPath(),
                    guessLambda = guessLambda,
                    guessLocal = guessLocal,
                    sortImports = sortImports,
                    srgs = srgs,
                    excs = exceptors,
                    keepImports = keepImports
                ).run()
            } catch (e: OptionException) {
                parser.printHelpOn(System.out)
                e.printStackTrace()
            }
        }
    },
    EXTRACT {
        val converter = PathConverter()

        override fun run(args: Array<String>) {
            val parser = OptionParser()

            val libArg = parser.acceptsAll(mutableListOf("e", "lib"))
                .withRequiredArg()
                .ofType(File::class.java)

            val inputArg = parser.acceptsAll(mutableListOf("in", "input"))
                .withRequiredArg()
                .withValuesConvertedBy(converter)
                .required()

            val outputArg: OptionSpec<Path> = parser.acceptsAll(mutableListOf("out", "output"))
                .withRequiredArg()
                .withValuesConvertedBy(converter)
                .required()

            val jvmVersionArg = parser.acceptsAll(mutableListOf("sc", "source-compatibility"))
                .withRequiredArg()
                .ofType(SourceVersion::class.java)
                .defaultsTo(SourceVersion.JAVA_1_8)
                .withValuesConvertedBy(object : ValueConverter<SourceVersion> {
                    override fun convert(value: String): SourceVersion? {
                        return SourceVersion.parse(value)
                    }

                    override fun valueType(): Class<out SourceVersion> {
                        return SourceVersion::class.java
                    }

                    override fun valuePattern(): String {
                        val ret: MutableList<String> = ArrayList()
                        for (v in SourceVersion.values()) {
                            ret.add(v.name)
                            ret.add(v.spec)
                        }
                        return ret.joinToString(",")
                    }
                })

            val fullPowerArg = parser.accepts("fullpower")
                .withOptionalArg()
                .ofType(Boolean::class.java)
                .defaultsTo(false)

            try {
                val options = parser.parse(*args)
                println("Compat: " + options.valueOf(jvmVersionArg))
                println("Output: " + options.valueOf(outputArg))

                val jvmVersion = options.valueOf(jvmVersionArg)
                val output = options.valueOf(outputArg)
                val libs = options.valuesOf(libArg).onEach { println("Lib:    $it") }
                val inputs = options.valuesOf(inputArg).onEach { println("Input:  $it") }
                val isFullPower = options.valueOf(fullPowerArg)
                if (isFullPower) {
                    println("Full Power:    true")
                }

                val extractor = KotlinRangeExtractor(
                    inputs = inputs.map { it.input() },
                    output = PrintWriter(output.toAbsolutePath().apply {
                        if (notExists()) {
                            try { parent.createDirectories() } catch (_: FileAlreadyExistsException) {}
                            createFile()
                        }
                    }.bufferedWriter()),
                    jvmVersion = jvmVersion
                )
                libs.forEach {
                    extractor.addLibrary(it)
                }
                extractor.run()
            } catch (e: OptionException) {
                parser.printHelpOn(System.out)
                e.printStackTrace()
            }
        }
    };

    abstract fun run(args: Array<String>)
}

fun Path.output(encoding: Charset = StandardCharsets.UTF_8): OutputSupplier {
    return try {
        if (Files.isDirectory(this)) {
            FolderSupplier.create(this, encoding)
        } else {
            ZipOutputSupplier(this)
        }
    } catch (e: IOException) {
        throw IllegalArgumentException("Invalid output: $this", e)
    }
}

fun Path.input(encoding: Charset = StandardCharsets.UTF_8): InputSupplier {
    require(Files.exists(this)) { "Invalid com.tmvkrpxl0.krange.input value: $this" }

    val filename: String = fileName.toString().lowercase()
    try {
        if (Files.isDirectory(this)) {
            return FolderSupplier.create(this, encoding)
        } else if (filename.endsWith(".jar") || filename.endsWith(".zip")) {
            return ZipInputSupplier.create(this, encoding)
        }

        throw IllegalArgumentException("Invalid com.tmvkrpxl0.krange.input value: $this")
    } catch (e: IOException) {
        throw IllegalArgumentException("Invalid com.tmvkrpxl0.krange.input: $this", e)
    }
}
