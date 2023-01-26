import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForModule
import org.jetbrains.kotlin.analyzer.ResolverForSingleModuleProject
import org.jetbrains.kotlin.analyzer.common.CommonAnalysisParameters
import org.jetbrains.kotlin.analyzer.common.CommonPlatformAnalyzerServices
import org.jetbrains.kotlin.analyzer.common.CommonResolverForModuleFactory
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.FilteringMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.common.setupCommonArguments
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleCapability
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import java.io.File
import java.io.PrintStream

class KotlinParser(
    logger: PrintStream,
    logWarnings: Boolean,
    classPaths: List<File>,
    kotlinRoot: String
) {
    private val messageCollector = FilteringMessageCollector(
        PrintingMessageCollector(logger, MessageRenderer.GRADLE_STYLE, true)
    ) { logWarnings || !it.isWarning }

    private val configuration = CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        val arguments = K2JVMCompilerArguments()
        arguments.verbose = true
        setupCommonArguments(arguments) { JvmMetadataVersion(*it) }

        configureJdkClasspathRoots()
        addJvmClasspathRoots(classPaths)
        addKotlinSourceRoot(kotlinRoot)
    }

    private val rootDisposable = Disposer.newDisposable()

    private val environment = KotlinCoreEnvironment.createForProduction(
        rootDisposable,
        configuration,
        EnvironmentConfigFiles.JVM_CONFIG_FILES
    )

    val files = environment.getSourceFiles()

    val project = environment.project

    fun getResolver(): ResolverForModule {
        val moduleInfo = SourceModuleInfo(Name.special("<main"), mapOf(), false)

        val resolverForModuleFactory = CommonResolverForModuleFactory(
            CommonAnalysisParameters({ content ->
                environment.createPackagePartProvider(content.moduleContentScope)
            }),
            CompilerEnvironment,
            CommonPlatforms.defaultCommonPlatform,
            shouldCheckExpectActual = false
        )

        val resolver = ResolverForSingleModuleProject(
            "sources for metadata serializer",
            ProjectContext(project, "metadata serializer"),
            moduleInfo,
            resolverForModuleFactory,
            GlobalSearchScope.allScope(project),
            languageVersionSettings = configuration.languageVersionSettings,
            syntheticFiles = files
        )

            // There are multiple interesting services, I might need to look into this...
            return resolver.resolverForModule(moduleInfo)
    }

    fun dispose() {
        rootDisposable.dispose()
        if (messageCollector.hasErrors()) {
            throw RuntimeException("Compilation error")
        }
    }
}

private class SourceModuleInfo(
    override val name: Name,
    override val capabilities: Map<ModuleCapability<*>, Any?>,
    private val dependOnOldBuiltIns: Boolean,
    override val analyzerServices: PlatformDependentAnalyzerServices = CommonPlatformAnalyzerServices,
    override val platform: TargetPlatform = CommonPlatforms.defaultCommonPlatform
) : ModuleInfo {
    override fun dependencies() = listOf(this)

    override fun dependencyOnBuiltIns(): ModuleInfo.DependencyOnBuiltIns =
        if (dependOnOldBuiltIns) {
            ModuleInfo.DependencyOnBuiltIns.LAST
        } else {
            ModuleInfo.DependencyOnBuiltIns.NONE
        }
}
