import net.minecraftforge.srg2source.RangeExtractMain
import kotlin.test.Test

class RunOriginal {
    @Test
    fun runOriginal() {
        RangeExtractMain.main(arrayOf(
            "--in", "src/main/java", // MUST BE PACKAGE ROOT!
            "--out", "output"
        ))
    }
}