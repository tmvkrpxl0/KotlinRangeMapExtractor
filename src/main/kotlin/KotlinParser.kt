import net.minecraftforge.srg2source.api.InputSupplier
import net.minecraftforge.srg2source.util.io.FolderSupplier
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.FilteringMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
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
import java.io.File
import java.io.PrintStream

class KotlinParser(
    logger: PrintStream,
    logWarnings: Boolean,
    additionalLibs: List<File>,
    inputs: InputSupplier
) {
    private val messageCollector = FilteringMessageCollector(
        PrintingMessageCollector(logger, MessageRenderer.GRADLE_STYLE, true)
    ) { logWarnings || !it.isWarning }
    private val configuration = CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        put(CommonConfigurationKeys.MODULE_NAME, "main")
        val arguments = K2JVMCompilerArguments()
        arguments.verbose = true
        setupCommonArguments(arguments) { JvmMetadataVersion(*it) }

        configureJdkClasspathRoots()
        addJvmClasspathRoots(javaClass.classLoader.getResources("kotlin/")
            .asSequence()
            .map { it.file.removePrefix("file:").substringBeforeLast('!') }
            .map { File(it).apply { require(exists()) } }
            .toList())
        addJvmClasspathRoots(additionalLibs)
        addKotlinSourceRoot(inputs.getRoot("/")!!) // TODO Handle properly when inputs are not from Directory
    }

    init {
        if (inputs !is FolderSupplier) messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, "Only FolderSupplier works for now!")
    }

    private val environment = KotlinCoreEnvironment.createForProduction(
        {  },
        configuration,
        EnvironmentConfigFiles.JVM_CONFIG_FILES
    )

    val project = environment.project

    val trace = CliBindingTrace()

    val files = environment.getSourceFiles()

    val result: AnalysisResult by lazy {
        val r = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(project, files, trace, configuration, environment::createPackagePartProvider)
        r.throwIfError()
        r
    }

    fun dispose() {
        if (messageCollector.hasErrors()) {
            throw RuntimeException("Compilation error")
        }
    }
}
