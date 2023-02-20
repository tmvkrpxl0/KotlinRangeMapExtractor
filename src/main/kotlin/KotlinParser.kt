import net.minecraftforge.srg2source.api.SourceVersion
import net.minecraftforge.srg2source.util.io.ConfLogger
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.setupCommonArguments
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.io.PrintStream

class KotlinParser(
    logger: ConfLogger<*>,
    logWarnings: Boolean,
    jvmVersion: SourceVersion,
    additionalLibs: List<File>,
    vararg optInAnnotations: String
) {
    private val messageCollector = FilteringMessageCollector(
        ErrorSeparatingCollector(logger.logger, logger.errorLogger, MessageRenderer.GRADLE_STYLE, true)
    ) { logWarnings || !it.isWarning }
    private val configuration = CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        put(CommonConfigurationKeys.MODULE_NAME, "main")

        val arguments = K2JVMCompilerArguments()
        arguments.verbose = true
        arguments.jvmTarget = jvmVersion.spec
        @Suppress("UNCHECKED_CAST")
        arguments.optIn = optInAnnotations as Array<String>
        setupCommonArguments(arguments) { JvmMetadataVersion(*it) }
        configureJdkClasspathRoots()

        // Add Kotlin stdlibs
        addJvmClasspathRoots(javaClass.classLoader.getResources("kotlin/")
            .asSequence()
            .map { it.file.removePrefix("file:").substringBeforeLast('!') }
            .map { File(it).apply { require(exists()) } }
            .toList())

        addJvmClasspathRoots(additionalLibs)
    }

    private val environment = KotlinCoreEnvironment.createForProduction(
        { },
        configuration,
        EnvironmentConfigFiles.JVM_CONFIG_FILES
    )

    val project = environment.project

    val trace = CliBindingTrace()

    fun analyze(files: List<KtFile>): AnalysisResult {
        val r = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            project,
            files,
            trace,
            configuration,
            environment::createPackagePartProvider
        )
        r.throwIfError()
        return r
    }

    fun dispose() {
        if (messageCollector.hasErrors()) {
            throw RuntimeException("Compilation error")
        }
    }
}

private class ErrorSeparatingCollector(
    private val logStream: PrintStream,
    private val errStream: PrintStream,
    private val messageRenderer: MessageRenderer,
    private val verbose: Boolean
) :
    MessageCollector {
    private var hasError = false
    override fun clear() {}

    override fun hasErrors() = hasError

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        if (!verbose && CompilerMessageSeverity.VERBOSE.contains(severity)) return

        this.hasError = this.hasError or severity.isError

        val rendered = messageRenderer.render(severity, message, location)
        if (severity.isError) {
            errStream.println(rendered)
        } else {
            logStream.println(rendered)
        }
    }

}
