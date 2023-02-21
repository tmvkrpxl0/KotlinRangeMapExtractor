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
    fun runKotlinExtract() {
        val libs = File("classpath.txt").readText().split('\n')
        val args = mutableListOf<String>()

        args += "--extract"
        libs.forEach {
            args += "--lib"
            args += it
        }
        args += "--in"
        args += "src/test/testkotlin" // MUST BE PACKAGE ROOT!
        args += "--out"
        args += "koutput"

        main(args.toTypedArray())
    }

    @Test
    fun applyKotlin() {
        main(arrayOf(
            "--apply",
            "--in", "src/test/testkotlin",
            "--out", "src/test/toanalyze",
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