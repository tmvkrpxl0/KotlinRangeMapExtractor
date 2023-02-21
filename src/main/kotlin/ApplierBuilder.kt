import net.minecraftforge.srg2source.api.InputSupplier
import net.minecraftforge.srg2source.api.OutputSupplier
import net.minecraftforge.srg2source.util.io.ChainedInputSupplier
import java.io.PrintStream
import java.nio.file.Path

fun buildApplier(
    logger: PrintStream = System.out,
    errorLogger: PrintStream = System.err,
    inputs: List<InputSupplier>,
    output: OutputSupplier,
    rangeMapPath: Path,
    guessLambda: Boolean = false,
    guessLocal: Boolean = false,
    sortImports: Boolean = false,
    srgs: List<Path> = emptyList(),
    excs: List<Path> = emptyList(),
    keepImports: Boolean = false
): KRangeApplier {
    val applier = KRangeApplier()

    applier.logger = logger
    applier.errorLogger = errorLogger
    if (inputs.size == 1) {
        applier.setInput(inputs.first())
    } else {
        applier.setInput(ChainedInputSupplier(inputs))
    }
    applier.setOutput(output)
    applier.readRangeMap(rangeMapPath)

    applier.setGuessLambdas(guessLambda)
    applier.setGuessLocals(guessLocal)
    applier.setSortImports((sortImports))

    srgs.forEach { applier.readSrg(it) }
    excs.forEach { applier.readExc(it) }

    applier.keepImports(keepImports)

    return applier
}
