import java.io.File

fun main() {
    val ramOutput = File("ktoutput").printWriter()
    val extractor = KotlinRangeExtractor(root="src/test/toanalyze", output=ramOutput, logWarnings = true)

    extractor.run()
}