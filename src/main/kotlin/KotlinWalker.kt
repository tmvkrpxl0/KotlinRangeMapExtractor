import com.intellij.codeWithMe.getStackTrace
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import net.minecraftforge.srg2source.range.RangeMapBuilder
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.util.isOrdinaryClass
import java.util.*

class KotlinWalker(private val builder: RangeMapBuilder) {
    fun onBlock(element: KtBlockExpression) = element.visitChildren()

    fun onBreak() {
        return
    }

    fun onFor(element: KtForExpression) {
        element.visitChildren()
        return
    }

    fun onDoc(element: KDoc) {
        element.visitChildren()
    }

    fun onPackage(element: KtPackageDirective) {
        if (element.isRoot) return
        builder.addPackageReference(element.startOffset, element.textLength, element.fqName.asString())
    }

    fun onNamedFunction(element: KtNamedFunction) {
        element.visitChildren()
    }

    fun onClass(element: KtClass) {
        // Modifiers in java: sealed, [access modifiers], final, static, abstract, non-sealed
        // Type Parameter: Thing<Int, String> <- Int and String
        // Internal name examples:
        // com.tmvkrpxl0.Test -> com/tmvkrpxl0/Test
        // com.tmvkrpxl0.Test.InnerClass -> com/tmvkrpxl0/Test$InnerClass

        val innerName = internalName(element)

        when {
            element.isInterface() -> builder.addInterfaceDeclaration(element.startOffset, element.textLength, innerName)
            element.isOrdinaryClass -> builder.addClassDeclaration(element.startOffset, element.textLength, innerName)
        }

        if (element.isInterface() || element.isOrdinaryClass) {
            element.modifierList?.let { visit(it) }

            val identifier = element.nameIdentifier!!
            builder.addClassReference(identifier.startOffset, identifier.textLength, identifier.text, innerName, false)

            element.typeParameterList?.visitChildren(true)
            element.getSuperTypeList()?.visitChildren(true)
            element.body?.let { visit(it) }
        }
    }

    fun onModifiers(modifiers: KtModifierList) {
        modifiers.visitChildren(true)
    }

    // In original srg2source, BodyDeclaration is for everything that can have body
    // subclass, functions, etc
    fun onBody(body: KtClassBody) {
        body.visitChildren(true)
    }

    // Visits children of each Type Parameter(seems to be always empty??), name, and type bounds
    fun onTypeParameter(typeParameter: KtTypeParameter) {
        typeParameter.visitChildren()
    }

    fun onImportList() {
        return
    }

    fun onWhiteSpace() {
        return
    }

    fun onString(element: KtStringTemplateExpression) {
        element.visitChildren()
    }

    fun onFile(file: KtFile) {
        file.visitChildren()
    }

    fun onVariable(element: KtVariableDeclaration) {
        element.visitChildren()
    }

    fun onCall(element: KtCallElement) {
        val methodOwner = element
    }

    fun onDotQualified(element: KtDotQualifiedExpression) {
        element.visitChildren()
    }

    fun visit(element: PsiElement) {
        when (element) {
            is KtNamedFunction -> onNamedFunction(element)
            is KtForExpression -> onFor(element)
            is KtBreakExpression -> onBreak()
            is KtPackageDirective -> onPackage(element)
            is KtBlockExpression -> onBlock(element)
            is KtClass -> onClass(element)
            is KtImportList -> onImportList()
            is PsiWhiteSpace -> onWhiteSpace()
            is KtFile -> onFile(element)
            is KDoc -> onDoc(element)
            is KtStringTemplateExpression -> onString(element)
            is KtVariableDeclaration -> onVariable(element)
            is KtModifierList -> onModifiers(element)
            is KtClassBody -> onBody(element)
            is KtCallElement -> onCall(element)
            is KtDotQualifiedExpression -> onDotQualified(element)
            else -> {
                println("Unhandled type: $element")
            }
        }
    }

    fun List<PsiElement>.visitAll(allowEmpty: Boolean = false) {
        if (!allowEmpty && isEmpty()) {
            println("EMPTY!")
            println(getStackTrace())
        }
        forEach {
            visit(it)
        }
    }

    fun PsiElement.visitChildren(allowEmpty: Boolean = false) {
        if (!allowEmpty && children.isEmpty()) {
            println("EMPTY!")
            println(getStackTrace())
        }
        children.forEach {
            visit(it)
        }
    }
}

private fun internalName(element: KtClass): String {
    val segments = LinkedList(element.fqName!!.pathSegments())
    return if (element.isInner()) {
        val last = segments.removeLast()
        segments.joinToString(separator = "/") + "$" + last
    } else {
        segments.joinToString(separator = "/")
    }
}

/*
* KtFile
*  KtPackageDirective
*  KtImportList
*  PsiWhiteSpace
*  KtClass
* */
/*
        @Override public boolean visit(AnnotationTypeDeclaration       node) { return process(node); }
        @Override public boolean visit(AnnotationTypeMemberDeclaration node) { return true; }
        @Override public boolean visit(AnonymousClassDeclaration       node) { return process(node); }
        @Override public boolean visit(ArrayAccess                     node) { return true; }
        @Override public boolean visit(ArrayCreation                   node) { return true; }
        @Override public boolean visit(ArrayInitializer                node) { return true; }
        @Override public boolean visit(ArrayType                       node) { return true; }
        @Override public boolean visit(AssertStatement                 node) { return true; }
        @Override public boolean visit(Assignment                      node) { return true; }
        - @Override public boolean visit(Block                           node) { return true; }
        - @Override public boolean visit(BlockComment                    node) { return true; }
        @Override public boolean visit(BooleanLiteral                  node) { return true; }
        - @Override public boolean visit(BreakStatement                  node) { return process(node); }
        @Override public boolean visit(CaseDefaultExpression           node) { return true; }
        @Override public boolean visit(CastExpression                  node) { return true; }
        @Override public boolean visit(CatchClause                     node) { return true; }
        @Override public boolean visit(CharacterLiteral                node) { return true; }
        @Override public boolean visit(ClassInstanceCreation           node) { return process(node); }
        @Override public boolean visit(ConditionalExpression           node) { return true; }
        @Override public boolean visit(ConstructorInvocation           node) { return true; }
        @Override public boolean visit(ContinueStatement               node) { return process(node); }
        @Override public boolean visit(CompilationUnit                 node) { return true; }
        @Override public boolean visit(CreationReference               node) { return true; }
        @Override public boolean visit(Dimension                       node) { return true; }
        @Override public boolean visit(DoStatement                     node) { return true; }
        @Override public boolean visit(EmptyStatement                  node) { return true; }
        - @Override public boolean visit(EnhancedForStatement            node) { return true; } There is only one for in kotlin
        @Override public boolean visit(EnumConstantDeclaration         node) { return true; }
        @Override public boolean visit(EnumDeclaration                 node) { return process(node); }
        @Override public boolean visit(ExportsDirective                node) { return true; }
        @Override public boolean visit(ExpressionMethodReference       node) { return true; }
        @Override public boolean visit(ExpressionStatement             node) { return true; }
        @Override public boolean visit(FieldAccess                     node) { return true; }
        @Override public boolean visit(FieldDeclaration                node) { return true; }
        - @Override public boolean visit(ForStatement                    node) { return true; }
        @Override public boolean visit(GuardedPattern                  node) { return true; }
        @Override public boolean visit(IfStatement                     node) { return true; }
        - @Override public boolean visit(ImportDeclaration               node) { return process(node); }
        @Override public boolean visit(InfixExpression                 node) { return true; }
        @Override public boolean visit(Initializer                     node) { return process(node); }
        @Override public boolean visit(InstanceofExpression            node) { return true; }
        @Override public boolean visit(PatternInstanceofExpression     node) { return true; }
        @Override public boolean visit(IntersectionType                node) { return true; }
        - @Override public boolean visit(Javadoc                         node) { return true; }
        @Override public boolean visit(LabeledStatement                node) { return process(node); }
        @Override public boolean visit(LambdaExpression                node) { return process(node); }
        - @Override public boolean visit(LineComment                     node) { return true; }
        @Override public boolean visit(MarkerAnnotation                node) { return process(node); }
        @Override public boolean visit(MethodDeclaration               node) { return process(node); }
        @Override public boolean visit(MethodInvocation                node) { return true; }
        @Override public boolean visit(MemberRef                       node) { return true; }
        @Override public boolean visit(MemberValuePair                 node) { return true; }
        @Override public boolean visit(MethodRef                       node) { return true; }
        @Override public boolean visit(MethodRefParameter              node) { return true; }
        @Override public boolean visit(Modifier                        node) { return true; }
        @Override public boolean visit(ModuleDeclaration               node) { return true; }
        @Override public boolean visit(ModuleModifier                  node) { return true; }
        @Override public boolean visit(ModuleQualifiedName             node) { return true; }
        @Override public boolean visit(NameQualifiedType               node) { return true; }
        @Override public boolean visit(NormalAnnotation                node) { return process(node); }
        @Override public boolean visit(NullLiteral                     node) { return true; }
        @Override public boolean visit(NullPattern                     node) { return true; }
        @Override public boolean visit(NumberLiteral                   node) { return true; }
        @Override public boolean visit(OpensDirective                  node) { return true; }
        @Override public boolean visit(PackageDeclaration              node) { return process(node); }
        @Override public boolean visit(ParameterizedType               node) { return true; }
        @Override public boolean visit(ParenthesizedExpression         node) { return true; }
        @Override public boolean visit(PostfixExpression               node) { return true; }
        @Override public boolean visit(PrefixExpression                node) { return true; }
        @Override public boolean visit(PrimitiveType                   node) { return true; }
        @Override public boolean visit(ProvidesDirective               node) { return true; }
        @Override public boolean visit(QualifiedName                   node) { return process(node); }
        @Override public boolean visit(QualifiedType                   node) { return true; }
        @Override public boolean visit(RequiresDirective               node) { return true; }
        @Override public boolean visit(RecordDeclaration               node) { return process(node); }
        @Override public boolean visit(ReturnStatement                 node) { return true; }
        @Override public boolean visit(SimpleName                      node) { return process(node); }
        @Override public boolean visit(SimpleType                      node) { return true; }
        @Override public boolean visit(SingleMemberAnnotation          node) { return process(node); }
        @Override public boolean visit(SingleVariableDeclaration       node) { return process(node); }
        - @Override public boolean visit(StringLiteral                   node) { return true; }
        @Override public boolean visit(SuperConstructorInvocation      node) { return true; }
        @Override public boolean visit(SuperFieldAccess                node) { return true; }
        @Override public boolean visit(SuperMethodInvocation           node) { return true; }
        @Override public boolean visit(SuperMethodReference            node) { return true; }
        @Override public boolean visit(SwitchCase                      node) { return true; }
        @Override public boolean visit(SwitchExpression                node) { return true; }
        @Override public boolean visit(SwitchStatement                 node) { return true; }
        @Override public boolean visit(SynchronizedStatement           node) { return true; }
        @Override public boolean visit(TagElement                      node) { return true; }
        @Override public boolean visit(TextBlock                       node) { return true; }
        @Override public boolean visit(TextElement                     node) { return true; }
        @Override public boolean visit(ThisExpression                  node) { return true; }
        @Override public boolean visit(ThrowStatement                  node) { return true; }
        @Override public boolean visit(TryStatement                    node) { return true; }
        @Override public boolean visit(TypeDeclaration                 node) { return process(node); }
        @Override public boolean visit(TypeDeclarationStatement        node) { return true; }
        @Override public boolean visit(TypeLiteral                     node) { return true; }
        @Override public boolean visit(TypeMethodReference             node) { return true; }
        @Override public boolean visit(TypeParameter                   node) { return true; }
        @Override public boolean visit(TypePattern                     node) { return true; }
        @Override public boolean visit(VariableDeclarationExpression   node) { return true; }
        @Override public boolean visit(VariableDeclarationFragment     node) { return process(node); }
        @Override public boolean visit(VariableDeclarationStatement    node) { return true; }
        @Override public boolean visit(UnionType                       node) { return true; }
        @Override public boolean visit(UsesDirective                   node) { return true; }
        @Override public boolean visit(WhileStatement                  node) { return true; }
        @Override public boolean visit(WildcardType                    node) { return true; }
        @Override public boolean visit(YieldStatement                  node) { return true; }
 */