import net.minecraftforge.srg2source.util.io.FolderSupplier
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

fun main() {
    val ramOutput = File("ktoutput").printWriter()
    val folder = FolderSupplier.create(Paths.get("src/test/toanalyze"), StandardCharsets.UTF_8)
    val extractor = KotlinRangeExtractor(folder, output=ramOutput, logWarnings = true)

    extractor.run()
}
