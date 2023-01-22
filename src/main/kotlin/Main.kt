import java.io.File

fun main() {
    val ramOutput = File("ktoutput").printWriter()
    val extractor = KotlinRangeExtractor(root="src/main/kotlin", output=ramOutput, logWarnings = true)

    extractor.run()
}