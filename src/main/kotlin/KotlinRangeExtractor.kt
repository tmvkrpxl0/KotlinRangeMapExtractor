import net.minecraftforge.srg2source.api.InputSupplier
import net.minecraftforge.srg2source.range.RangeMap
import net.minecraftforge.srg2source.range.RangeMapBuilder
import net.minecraftforge.srg2source.util.Util
import net.minecraftforge.srg2source.util.io.ConfLogger
import net.minecraftforge.srg2source.util.io.FolderSupplier
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalVirtualFile
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

class KotlinRangeExtractor(
    private val input: InputSupplier,
    var output: PrintWriter? = null,
    val logWarnings: Boolean = false,
) : ConfLogger<KotlinRangeExtractor>() {
    private val libs: MutableSet<File> = LinkedHashSet()
    private val fileCache: MutableMap<String, RangeMap> = HashMap() // Relative Path from package root -> RangeMap
    private val parser = KotlinParser(logger, logWarnings, libs.toList(), input)
    var cacheHits = 0
        private set

    fun addLibrary(value: File) {
        val fileName = value.path.lowercase()
        if (!value.exists()) {
            error("Missing Library: " + value.absolutePath)
        } else if (value.isDirectory) {
            libs.add(value) // Root directories, for dev time classes.
        } else if (fileName.endsWith(".jar") || fileName.endsWith(".jar")) {
            libs.add(value)
        } else log("Unsupposrted library path: " + value.absolutePath)
    }

    fun addCache(stream: InputStream?) {
        fileCache.putAll(RangeMap.readAll(stream))
    }

    //Log everything as a comment in case we merge the output and log as we used to do.
    public override fun log(message: String) {
        super.log("# $message")
    }

    /**
     * Generates the rangemap.
     */
    fun run(): Boolean {
        log("Symbol range map extraction starting")
        val files = input.gatherAll(".kt")
            .map { it.replace("\\", "/") }
            .sorted()
        return generate(files)
    }

    private fun generate(paths: List<String>): Boolean {
        // TODO  Input supplier's root may be relative location, but VirtualFileSystem uses absolute path
        // and it might not even be local file system because ZipInputSupplier exist
        if (input !is FolderSupplier) errorLogger.println("Currently only Folder supplier works!")
        val ktFiles = paths.map { path ->
            parser.files.find { file ->
                val root = input.getRoot(path)!!
                val absoluteRoot = File(root).resolve(path)
                file.virtualFilePath == absoluteRoot.absolutePath
            }!!
        }
        try {
            ktFiles.forEach { ktFile ->
                val path = ktFile.virtualFilePath
                val encoding = input.getEncoding(path) ?: StandardCharsets.UTF_8
                val code = input.getInput(path)!!.bufferedReader(encoding).readText()

                val md5 = Util.md5(code, StandardCharsets.UTF_8)
                val builder = RangeMapBuilder(this, path, md5)

                // TODO Implement caching

                log("start Processing \"$path\" md5: $md5")

                ktFile.accept(KotlinWalker(builder, parser.result))

                val rangeMap = builder.build()
                if (output != null) {
                    rangeMap.write(output, true)
                }
                log("End processing \"$path\"")
                log("")
            }
        } catch (e: Throwable) {
            e.printStackTrace(errorLogger)
        }

        cleanup()
        return true
    }

    private fun cleanup() {
        if (output != null) {
            output!!.flush()
            output!!.close()
            output = null
        }
        parser.dispose()
    }
}
