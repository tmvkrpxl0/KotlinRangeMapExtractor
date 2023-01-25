import net.minecraftforge.srg2source.range.RangeMap
import net.minecraftforge.srg2source.range.RangeMapBuilder
import net.minecraftforge.srg2source.util.Util
import net.minecraftforge.srg2source.util.io.ConfLogger
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

class KotlinRangeExtractor(
    private val root: String,
    var output: PrintWriter? = null,
    val logWarnings: Boolean = false,
) : ConfLogger<KotlinRangeExtractor>() {
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
        return generate(root)
    }

    private fun generate(root: String): Boolean {
        try {
            val parser = KotlinParser(logger, logWarnings, libs.toList(), root)

            val (resolver, files) = parser.getResolverAndFiles()

            val bindingContext = resolver.componentProvider.get<BindingTrace>().bindingContext
            val analyzer = resolver.componentProvider.get<LazyTopDownAnalyzer>()
            val result = analyzer.analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files)
            result.files.forEach {
                val md5 = Util.md5(it.text, StandardCharsets.UTF_8)
                val sourcePath = it.getSourcePath()

                val builder = RangeMapBuilder(this, sourcePath, md5)

                // TODO Implement caching

                log("start Processing \"$sourcePath\" md5: $md5")

                KotlinWalker(builder, bindingContext).visit(it)
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
        if (output != null) {
            output!!.flush()
            output!!.close()
            output = null
        }
    }

    fun KtFile.getSourcePath(): String {
        return if (packageFqName.isRoot) name
        else "$packageName.$name"
    }
}
