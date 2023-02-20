import net.minecraftforge.srg2source.api.SourceVersion
import net.minecraftforge.srg2source.util.io.FolderSupplier
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

fun main() {
    val ramOutput = File("ktoutput").printWriter()
    val folder = FolderSupplier.create(Paths.get("src/test/testkotlin"), StandardCharsets.UTF_8)
    val extractor = KotlinRangeExtractor(
        inputs=listOf(folder),
        output=ramOutput,
        jvmVersion = SourceVersion.JAVA_17,
        logWarnings = true
    )
    File("classpath.txt").useLines {
        it.forEach {  path ->
            extractor.addLibrary(File(path))
        }
    }

    extractor.run()
}
