import kastree.ast.Node
import kastree.ast.psi.Converter
import org.jetbrains.kotlin.cfg.getDeclarationDescriptorIncludingConstructors
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.children
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import java.util.*

class CustomConverter : Converter() {
    private val modifiersByText = Node.Modifier.Keyword.values().associateBy { it.name.lowercase(Locale.getDefault()) }
    override fun onNode(node: Node, elem: PsiElement) {
        node.tag = Triple(elem.startOffset, elem.textLength, elem.text)
    }

    override fun convertConst(v: KtConstantExpression): Node.Expr.Const {
        return super.convertConst(v).apply { tag = Triple(v.startOffset, v.textLength, v.text) }
    }

    override fun convertTypeRef(v: KtTypeElement): Node.TypeRef {
        return super.convertTypeRef(v)
    }

    override fun convertBinaryOp(v: KtQualifiedExpression): Node.Expr.BinaryOp {
        return super.convertBinaryOp(v).apply { oper.tag = Triple(v.startOffset, v.textLength, v.text) }
    }

    override fun convertModifiers(v: KtModifierList?) = v?.node?.children().orEmpty().mapNotNull { node ->
        // We go over the node children because we want to preserve order
        when (val element = node.psi) {
            is KtAnnotationEntry -> Node.Modifier.AnnotationSet(
                target = element.useSiteTarget?.let(::convertAnnotationSetTarget),
                anns = listOf(convertAnnotation(element))
            ).map(element)

            is KtAnnotation -> convertAnnotationSet(element)
            is PsiWhiteSpace -> null
            else -> when (node.text) {
                // We ignore some modifiers because we handle them elsewhere
                "enum", "companion" -> null
                else -> modifiersByText[node.text]?.let { keyword ->
                    Node.Modifier.Lit(keyword).map(node.psi)
                } ?: error("Unrecognized modifier: ${node.text}")
            }
        }
    }.toList()

    data class Tag(
        val start: Int,
        val length: Int,
        val text: String,
    )
}