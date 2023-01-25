import com.intellij.codeWithMe.getStackTrace
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import net.minecraftforge.srg2source.range.RangeMapBuilder
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.cfg.containingDeclarationForPseudocode
import org.jetbrains.kotlin.cfg.getDeclarationDescriptorIncludingConstructors
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.load.kotlin.computeJvmDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isError
import org.jetbrains.kotlin.util.isOrdinaryClass
import java.util.*

// TODO Figure out how to resolve origin of givin element
class KotlinWalker(private val builder: RangeMapBuilder, private val bindingContext: BindingContext) {
    fun onBlock(element: KtBlockExpression) {
        element.visitChildren()
    }

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

    /**
     * Called when new function with "fun" keyword is being defined.
     */
    fun onNamedFunction(element: KtNamedFunction) {
        element.visitChildren()
    }

    // Huh, valueParameters returns empty list when lambda has "it" as parameter
    // This will be an issue....
    fun onLambda(element: KtLambdaExpression) {
        val name = element
        // TODO Add declaration
        // builder.addMethodDeclaration(element.startOffset, element.textLength, )

        element.valueParameters.visitAll(false)
        visit(element.bodyExpression!!) // why is this nullable?
    }

    fun onClass(element: KtClass) {
        // Modifiers in java: sealed, [access modifiers], final, static, abstract, non-sealed
        // Type Parameter: Thing<Int, String> <- Int and String
        // Internal name examples:
        // com.tmvkrpxl0.Test -> com/tmvkrpxl0/Test
        // com.tmvkrpxl0.Test.InnerClass -> com/tmvkrpxl0/Test$InnerClass

        val innerName = internalClassName(element)

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

    /**
     * Called when there is modifier such as visibility modifiers, inner keyword
     */
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

    /**
     * Called when there is import. Ignored in original range map extractor
     */
    fun onImportList() {
        return
    }

    /**
     * Called when there is white space such as new line, space, tab, etc...
     */
    fun onWhiteSpace() {
        return
    }

    /**
     * Called when there is any kind of String, either multiline or not.
     * This does not include char with ', it must be "
     */
    fun onString() {
        return
    }

    /**
     * Called when it starts compiling a file, kind of like entry point of compilation.
     */
    fun onFile(file: KtFile) {
        file.visitChildren()
    }

    /**
     * Called when there is field declaration, either var or val of any kind.
     * This includes extension property and destructing, etc...
     * This excludes constructor parameter.
     * There's another interface called `KtValVarKeywordOwner` which can be useful for above purpose.
     * If it's extension variable, for now let's just convert "Class.varName" to "Class$varName"
     * I... really don't think anyone using kotlin would legit use $
     */
    fun onVariable(element: KtVariableDeclaration) {
        val isField = element is KtDestructuringDeclarationEntry ||
                element.isTopLevelKtOrJavaMember() ||
                (element as KtProperty).isMember


        if (!isField) {
            if (localVariableTracker.isEmpty()) localVariableTracker.push(LinkedList())
            val localVars = localVariableTracker.peek()

            val variableDescriptor: VariableDescriptor =
                element.getDeclarationDescriptorIncludingConstructors(bindingContext)
                        as VariableDescriptor // I'm unsure if this case is safe
            localVars += variableDescriptor

            val method = element.containingDeclarationForPseudocode!!

            val classOrFileName = element.containingClassOrObject?.let { internalClassName(it) }
                ?: internalFileName(element.containingKtFile)

            if ((method as KtNamed).nameAsName!!.isAnonymous) {
                println("TODO: Implement lambda name resolver")
            } else {
                val enclosingDescriptor = method.getDeclarationDescriptorIncludingConstructors(bindingContext)
                        as FunctionDescriptor

                require(!variableDescriptor.type.isError)

                builder.addLocalVariableReference(
                    element.startOffset,
                    element.textLength,
                    element.name!!,
                    classOrFileName,
                    method.name,
                    enclosingDescriptor.computeJvmDescriptor(),
                    localVars.lastIndex,
                    getTypeSignature(variableDescriptor.type),
                    //element.typeReference!!.name!! // Type was Ljava/util/function/Consumer; or I in the test
                )
            }
        } else {

        }
        println("On variable enclosing element: ${element.parent}")
        element.visitChildren()
    }

    fun onDestructing(element: KtDestructuringDeclaration) {
        element.visitChildren()
    }

    /**
     * Called when there is function call. element itself is only about method name, and it has no field/class name.
     * There are 4 types of this<br>
     *
     * * KtAnnotationEntry -> when there is annotation on an element. this excludes file annotation.
     * * KtCallExpression -> when there is function invocation.
     * * KtConstructorDelegationCall -> in `Class Child: Super(1)`, `Super(1)` part is the supplied element.
     * * KtSuperTypeCallEntry -> TODO! currently unknown
     */
    fun onCall(element: KtCallElement) {
        // TODO figure out how to get origin of callee expression
        element.calleeExpression!!.getCall(bindingContext)
        val owner = element.calleeExpression!!
    }

    /**
     * Called when there is expression with dots, such as `Class.forName`
     * This also includes field access, both static and non-static
     * However this does not include extension functions declaration.
     */
    fun onDotQualified(element: KtDotQualifiedExpression) {
        element.visitChildren()
    }

    /**
     * Called when there is:
     * * Condition, such as ==, >=
     * * Loop range
     * * Label qualifier, such as `break@forEach`
     * * Indicies, TODO figure out this
     * ----------------------------------
     * * If
     * * Else
     * * Body of If statement, for If, Else-If, Else
     */
    fun onSpecial(element: KtContainerNode) {
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
            is KtStringTemplateExpression -> onString()
            is KtVariableDeclaration -> onVariable(element)
            is KtModifierList -> onModifiers(element)
            is KtClassBody -> onBody(element)
            is KtCallElement -> onCall(element)
            is KtDotQualifiedExpression -> onDotQualified(element)
            is KtDestructuringDeclaration -> onDestructing(element)
            is KtLambdaExpression -> onLambda(element)
            is KtContainerNode -> onSpecial(element)
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

    private val localVariableTracker = Stack<MutableList<VariableDescriptor>>()
}

private fun internalFileName(element: KtFile): String {
    return element.packageFqName.pathSegments().joinToString(separator = "/") + "/${element.name}"
}
private fun internalClassName(element: KtClassOrObject): String {
    val segments = LinkedList(element.fqName!!.pathSegments())
    val isInner = element is KtClass && element.isInner()

    return if (isInner) {
        val last = segments.removeLast()
        segments.joinToString(separator = "/") + "$" + last
    } else {
        segments.joinToString(separator = "/")
    }
}

// Get the full binary name, including L; wrappers around class names
fun getTypeSignature(type: KotlinType): String {
    return "L${type.getJetTypeFqName(false).replace('.', '/')};"
}

// Notes on how original srg2source handles variables:
// Both Eclipse compiler's and Kotlin compiler's variable declaration is composed of 2 parts:
// Variable declaration and Name
// Original srg2source works by saving variable in a map on "Variable declaration"
// and write it into range map on "Name", by dynamically analyzing what kind of name it is
// I think I can instead write it into range map on "Variable declaration"?
// Also, srg2source treats local variable declaration and reference as same thing
// and static and non-static variable as same thing

// There is a class called StructuralEntry. It adds indent, "# START", and "# END" to output range file.
// StructureEntry defines what can be StructureEntry, it's things like method, class, etc...
// I was thinking of somehow using that system to define property function of val/var
// But it seems impossible

/**
 * KtFile
 *  KtPackageDirective
 *  KtImportList
 *  PsiWhiteSpace
 *  KtClass
 * */

/**
 * FOR
 * BODY
 * BLOCK
 */
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