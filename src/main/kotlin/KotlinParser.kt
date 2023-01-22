import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForSingleModuleProject
import org.jetbrains.kotlin.analyzer.common.CommonAnalysisParameters
import org.jetbrains.kotlin.analyzer.common.CommonPlatformAnalyzerServices
import org.jetbrains.kotlin.analyzer.common.CommonResolverForModuleFactory
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.FilteringMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.container.tryGetService
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleCapability
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.resolve.*
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
    ) { severity ->
        !logWarnings && severity == CompilerMessageSeverity.WARNING
    }

    val configuration = CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        configureJdkClasspathRoots()
        addJvmClasspathRoots(classPaths)
        addKotlinSourceRoot(kotlinRoot)
    }

    fun parse(): TopDownAnalysisContext {
        val rootDisposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(
                rootDisposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            val ktFiles = environment.getSourceFiles()

            val moduleInfo = SourceModuleInfo(Name.special("<main"), mapOf(), false)
            val project = ktFiles.firstOrNull()?.project ?: throw AssertionError("No files to analyze")

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
                syntheticFiles = ktFiles
            )

            val container = resolver.resolverForModule(moduleInfo).componentProvider

            val lazyTopDownAnalyzer = container.tryGetService(LazyTopDownAnalyzer::class.java) as LazyTopDownAnalyzer
            return lazyTopDownAnalyzer.analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, ktFiles)
        } finally {
            rootDisposable.dispose()
            if (messageCollector.hasErrors()) {
                throw RuntimeException("Compilation error")
            }
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
