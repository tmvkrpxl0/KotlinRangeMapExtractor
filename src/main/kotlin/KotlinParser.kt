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
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
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
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import org.jetbrains.kotlin.resolve.TopDownAnalysisContext
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class KotlinParser {
    val configuration = CompilerConfiguration()


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

    companion object {
        private val LOG = Logger.getLogger(KotlinParser::class.java.name)

        private val messageCollector = object : MessageCollector {
            private var hasErrors = false
            override fun clear() {
                hasErrors = false
            }

            override fun hasErrors(): Boolean {
                return hasErrors
            }

            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                val text = if (location != null) {
                    val path = location.path
                    val position = "$path: (${location.line}, ${location.column}) "
                    position + message
                } else {
                    message
                }
                LOG.info(text)
            }
        }
    }

    fun parse(): TopDownAnalysisContext {
        // The Kotlin compiler configuration
        val groupingCollector = GroupingMessageCollector(messageCollector, false)
        val severityCollector = GroupingMessageCollector(groupingCollector, false)
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, severityCollector)
        configuration.addJvmClasspathRoots(PathUtil.getJdkClassesRootsFromCurrentJre())

        val rootDisposable = Disposer.newDisposable()

        try {
            val environment = KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val ktFiles = environment.getSourceFiles()

            val moduleInfo = SourceModuleInfo(Name.special("<main"), mapOf(), false)
            val project = ktFiles.firstOrNull()?.project ?: throw AssertionError("No files to analyze")

            val multiplatformLanguageSettings = object : LanguageVersionSettings by configuration.languageVersionSettings {
                override fun getFeatureSupport(feature: LanguageFeature): LanguageFeature.State =
                    if (feature == LanguageFeature.MultiPlatformProjects) LanguageFeature.State.ENABLED
                    else configuration.languageVersionSettings.getFeatureSupport(feature)
            }

            val resolverForModuleFactory = CommonResolverForModuleFactory(
                CommonAnalysisParameters { content ->
                    environment.createPackagePartProvider(content.moduleContentScope)
                },
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
                languageVersionSettings = multiplatformLanguageSettings,
                syntheticFiles = ktFiles
            )

            val container = resolver.resolverForModule(moduleInfo).componentProvider

            val lazyTopDownAnalyzer = container.tryGetService(LazyTopDownAnalyzer::class.java) as LazyTopDownAnalyzer
            return lazyTopDownAnalyzer.analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, ktFiles)
        } finally {
            rootDisposable.dispose()
            if (severityCollector.hasErrors()) {
                // TODO Properly show compile error
                throw RuntimeException("Compilation error")
            }
        }
    }
}