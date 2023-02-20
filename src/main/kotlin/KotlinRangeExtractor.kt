import net.minecraftforge.srg2source.api.InputSupplier
import net.minecraftforge.srg2source.api.SourceVersion
import net.minecraftforge.srg2source.range.RangeMap
import net.minecraftforge.srg2source.range.RangeMapBuilder
import net.minecraftforge.srg2source.util.Util
import net.minecraftforge.srg2source.util.io.ChainedInputSupplier
import net.minecraftforge.srg2source.util.io.ConfLogger
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

class KotlinRangeExtractor(
    inputs: List<InputSupplier>,
    var output: PrintWriter? = null,
    val logWarnings: Boolean = false,
    val fullPower: Boolean = false,
    private val jvmVersion: SourceVersion
) : ConfLogger<KotlinRangeExtractor>() {
    private val input = if (inputs.size == 1) inputs.first() else ChainedInputSupplier(inputs)
    private val libs: MutableSet<File> = LinkedHashSet()
    private val fileCache: MutableMap<String, RangeMap> = HashMap() // Relative Path from package root -> RangeMap

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
        return generate()
    }

    private fun generate(): Boolean {
        val files = input.gatherAll(".kt")
            .map { it.replace("\\", "/") }
            .sorted()

        val parser = KotlinParser(this, logWarnings, jvmVersion, libs.toList())
        val factory = PsiFileFactory.getInstance(parser.project)

        val ktFiles = files.map { path ->
            val contents = input.getInput(path)!!.bufferedReader().use { it.readText() }
            val name = path.substringAfterLast('/')
            factory.createFileFromText(name, KotlinFileType.INSTANCE, contents) as KtFile
        }
        val analysisResult = parser.analyze(ktFiles)
        val toAnalyze = files.zip(ktFiles)

        try {
            toAnalyze.forEach { (path, ktFile) ->
                val encoding = input.getEncoding(path) ?: StandardCharsets.UTF_8

                val md5 = Util.md5(ktFile.text, encoding)
                val builder = RangeMapBuilder(this, path, md5)

                if (builder.loadCache(fileCache[path])) {
                    log("Found cached source $path")
                    cacheHits++
                }

                log("start Processing \"$path\" md5: $md5")

                ktFile.accept(KotlinWalker(builder, this, analysisResult, compatibility=!fullPower))

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

        cleanup(parser)
        return true
    }

    private fun cleanup(parser: KotlinParser) {
        if (output != null) {
            output!!.flush()
            output!!.close()
            output = null
        }
        parser.dispose()
    }
}
