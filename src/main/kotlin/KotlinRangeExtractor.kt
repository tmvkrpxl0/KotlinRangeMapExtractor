import net.minecraftforge.srg2source.api.InputSupplier
import net.minecraftforge.srg2source.range.RangeMap
import net.minecraftforge.srg2source.range.RangeMapBuilder
import net.minecraftforge.srg2source.util.Util
import net.minecraftforge.srg2source.util.io.ConfLogger
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

class KotlinRangeExtractor : ConfLogger<KotlinRangeExtractor>() {
    var output: PrintWriter? = null
    val libs: MutableSet<File> = LinkedHashSet()
    var input: InputSupplier? = null
    var fileCache: Map<String, RangeMap> = HashMap()
    var cacheHits = 0
        private set
    var logWarnings = false
    var enablePreview = false

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

    @Throws(IOException::class)
    fun loadCache(stream: InputStream?) {
        fileCache = RangeMap.readAll(stream)
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
        val files = input!!.gatherAll(".kt")
            .map { f: String -> f.replace("\\\\".toRegex(), "/") } // Normalize directory separators.
            .sorted()
            .toTypedArray()
        log("Processing " + files.size + " files")
        if (files.isEmpty()) {
            // no files? well.. nothing to do then.
            cleanup()
            return true
        }
        return legacyGenerate(files)
    }

    private fun legacyGenerate(files: Array<String>): Boolean {
        try {
            val parser = KotlinParser()
            libs.forEach {
                parser.configuration.addJvmClasspathRoot(it)
            }

            files.forEach { parser.configuration.addKotlinSourceRoot(it) }

            val analyzedTree = parser.parse()
            analyzedTree.files.forEach {
                val md5 = Util.md5(it.text, StandardCharsets.UTF_8)
                val sourcePath = it.getSourcePath()

                val builder = RangeMapBuilder(this, sourcePath, md5)

                log("start Processing \"$sourcePath\" md5: $md5")

                KotlinWalker(builder).visit(it)
                val rangeMap = builder.build()
                if (output != null) {
                    rangeMap.write(output, true)
                }
                log("End processing \"$sourcePath\"")
                log("")
            }
        } catch (e: Exception) {
            e.printStackTrace(errorLogger)
        }
        cleanup()
        return true
    }

    private fun cleanup() {
        try {
            input!!.close()
        } catch (e: IOException) {
            e.printStackTrace(errorLogger)
        }
        if (output != null) {
            output!!.flush()
            output!!.close()
            output = null
        }
    }

    companion object {
        private var INSTANCE: KotlinRangeExtractor? = null
        fun KtFile.getSourcePath(): String {
            return if (packageFqName.isRoot) name
            else "$packageName.$name"
        }
    }
}
