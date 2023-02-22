import net.minecraftforge.srg2source.RangeApplyMain
import net.minecraftforge.srg2source.RangeExtractMain
import net.minecraftforge.srgutils.IMappingFile
import java.io.File
import kotlin.test.Test
import com.tmvkrpxl0.krange.main as krangeMain

class TestBehavior {
    @Test
    fun runExtract() {
        val libs = File("classpath.txt").readText().split('\n')
        val args = mutableListOf<String>()
        libs.forEach {
            args += "--lib"
            args += it
        }
        args += "--in"
        args += "src/test/testjava" // MUST BE PACKAGE ROOT!
        args += "--out"
        args += "output"

        RangeExtractMain.main(args.toTypedArray())
    }

    @Test
    fun runKotlinExtract() {
        val libs = File("classpath.txt").readText().split('\n')
        val args = mutableListOf<String>()

        args += "--extract"
        libs.forEach {
            args += "--lib"
            args += it
        }
        args += "--in"
        args += "/home/tmvkrpxl0/IdeaProjects/tcombat_forge/src/main/kotlin/" // MUST BE PACKAGE ROOT!
        args += "--out"
        args += "koutput"

        krangeMain(args.toTypedArray())
    }

    @Test
    fun runKotlinExtractSimple() {
        val libs = File("classpath.txt").readText().split('\n')
        val args = mutableListOf<String>()

        args += "--extract"
        libs.forEach {
            args += "--lib"
            args += it
        }
        args += "--in"
        args += "src/test/testkotlin/" // MUST BE PACKAGE ROOT!
        args += "--out"
        args += "kshort"

        krangeMain(args.toTypedArray())
    }

    @Test
    fun applyKotlin() {
        krangeMain(arrayOf(
            "--apply",
            "--in", "/home/tmvkrpxl0/IdeaProjects/tcombat_forge/src/main/kotlin",
            "--out", "/home/tmvkrpxl0/IdeaProjects/srg_test/src/main/kotlin/",
            "--map", "./bigboi.tsrg",
            "--range", "./koutput",
        ))
    }

    @Test
    fun applyJava() {
        RangeApplyMain.main(arrayOf(
            "--in", "src/test/testjava",
            "--out", "src/test/toanalyze",
            "--map", "/home/tmvkrpxl0/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.19.3-20221207.122022/srg_to_official_1.19.3.tsrg",
            "--range", "./output",
        ))
    }

    @Test
    fun mixMatchStuff() {
        val srgToOfficial = IMappingFile.load(File("/home/tmvkrpxl0/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.19.3-20221207.122022/srg_to_official_1.19.3.tsrg"))
        val obfuscatedToSrg = IMappingFile.load(File("/home/tmvkrpxl0/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.19.3-20221207.122022/obf_to_srg.tsrg2"))
        val obfuscatedToIntermediate = IMappingFile.load(File("/home/tmvkrpxl0/.gradle/caches/fabric-loom/1.19.3/intermediary-v2.tiny"))
        val intermediateToYarn = IMappingFile.load(File("/home/tmvkrpxl0/.gradle/caches/fabric-loom/1.19.3/net.fabricmc.yarn.1_19_3.1.19.3+build.5-v2/mappings-base.tiny"))

        val officialToObfuscated = obfuscatedToSrg.chain(srgToOfficial).reverse()
        val obfuscatedToYarn = obfuscatedToIntermediate.chain(intermediateToYarn)

        val final = officialToObfuscated.chain(obfuscatedToYarn)
        final.write(File("./bigboi.tsrg").toPath(), IMappingFile.Format.TSRG2, false)
    }
}