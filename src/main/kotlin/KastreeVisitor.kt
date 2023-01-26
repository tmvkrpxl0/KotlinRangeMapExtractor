import kastree.ast.Node
import kastree.ast.Visitor
import net.minecraftforge.srg2source.range.RangeMapBuilder
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.builtins.isSuspendFunctionType
import org.jetbrains.kotlin.cfg.containingDeclarationForPseudocode
import org.jetbrains.kotlin.cfg.getDeclarationDescriptorIncludingConstructors
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.source.toSourceElement
import org.jetbrains.kotlin.types.TypeUtils
import java.util.*

class KastVisitor(val builder: RangeMapBuilder) : Visitor() {
    override fun visit(v: Node?, parent: Node) {
        var earlyReturn = false

        if (v == null) earlyReturn = true

        if (earlyReturn) return super.visit(null, parent)
        requireNotNull(v)

        if (v.tag == null) {
            println("index information lost for $v")
            return
        }

        val (start, length, text) = v.info
        when(v) {
            is Node.File, is Node.Import -> {

            }
            is Node.Modifier.AnnotationSet -> {
                return
            }
            is Node.Package -> {
                builder.addPackageReference(start, length, v.names.joinToString("."))
            }
            is Node.Expr.Call.TrailLambda -> {
                println("what is this? :$v")
            }
            is Node.Type -> {
                println("${v.ref}")
            }
            is Node.Decl.Property -> {
                val isField = parent is Node.File || parent is Node.Decl.Structured

                if (!isField) {
                    v.vars
                }

                println("property parent: ${parent.javaClass}")
            }
            else -> {
                println("UNHANDLED ELEMENT: $v ${v.javaClass.name} ${v.info.third}")
            }
        }
        super.visit(v, parent)
    }

    private val Node.info: Triple<Int, Int, String>
        get() = tag as Triple<Int, Int, String>
}