import net.minecraftforge.srg2source.RangeApplyMain
import net.minecraftforge.srg2source.RangeExtractMain
import java.io.File
import kotlin.test.Test

class RunOriginal {
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
    fun applyKotlin() {
        RangeApplyMain.main(arrayOf(
            "--in", "/home/tmvkrpxl0/IdeaProjects/srg_test/src/main/kotlin",
            "--out", "/home/tmvkrpxl0/IdeaProjects/srg_test/src/main/kotlin",
            "--map", "/home/tmvkrpxl0/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.19.3-20221207.122022/srg_to_official_1.19.3.tsrg",
            "--range", "./ktoutput",
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
}