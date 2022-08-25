import net.minecraftforge.srg2source.util.io.FolderSupplier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import kotlin.io.path.Path

fun main() {
    val extractor = KotlinRangeExtractor()
    val ramOutput = File("ktoutput").printWriter()
    val input = FolderSupplier.create(Path("./"), StandardCharsets.UTF_8)
    extractor.output = ramOutput
    extractor.input = input
    extractor.logWarnings = true

    extractor.run()
}