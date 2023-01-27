import net.minecraftforge.srg2source.range.RangeMapBuilder
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.cfg.containingDeclarationForPseudocode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.load.kotlin.computeJvmDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.bindingContextUtil.getAbbreviatedTypeOrType
import java.util.*

// TODO Figure out how to resolve origin of givin element
class KotlinWalker(private val builder: RangeMapBuilder, val result: AnalysisResult): KtVisitorVoid() {
    val bindingContext = result.bindingContext

    /*val analyzer = serviceResolver.componentProvider.get<LazyTopDownAnalyzer>()
    val resolveSession = serviceResolver.componentProvider.get<ResolveSession>()
    val expressionTypes = serviceResolver.componentProvider.get<ExpressionTypingServices>()
    val localVarResolver = serviceResolver.componentProvider.get<LocalVariableResolver>()
    val typeChecker = serviceResolver.componentProvider.get<KotlinTypeChecker>()
    val overrides = serviceResolver.componentProvider.get<OverrideResolver>()
    val types = serviceResolver.componentProvider.get<TypeResolver>()
    val bindingTrace = serviceResolver.componentProvider.get<BindingTraceContext>()
    val dataflows = serviceResolver.componentProvider.get<DataFlowValueFactory>()
    val declarations = serviceResolver.componentProvider.get<DeclarationResolver>()
    val expressionTypeVisitor = serviceResolver.componentProvider.get<ExpressionTypingVisitor>()
    val descriptions = serviceResolver.componentProvider.get<DescriptorResolver>()
    val expressionTypeDispatcher = serviceResolver.componentProvider.get<ExpressionTypingVisitorDispatcher>()
    val builtIns = serviceResolver.componentProvider.get<KotlinBuiltIns>()
    val lazyDeclarations = serviceResolver.componentProvider.get<LazyDeclarationResolver>()
    val localDescriptions = serviceResolver.componentProvider.get<LocalDescriptorResolver>()
    val functions = serviceResolver.componentProvider.get<FunctionDescriptorResolver>()
    val annotations = serviceResolver.componentProvider.get<AnnotationResolver>()*/

    override fun visitPackageDirective(directive: KtPackageDirective) {
        handled = true
        if (!directive.isRoot) builder.addPackageReference(directive.startOffset, directive.textLength, directive.fqName.asString())
        return super.visitPackageDirective(directive)
    }

    override fun visitLambdaExpression(lambda: KtLambdaExpression) {
        val descriptor = bindingContext[BindingContext.FUNCTION, lambda]!!
        builder.addMethodDeclaration(lambda.startOffset, lambda.textLength, descriptor.name.asString(), descriptor.computeJvmDescriptor())

        handled = true
        lambda.valueParameters.visitAll(true)
        lambda.bodyExpression!!.accept(this)
    }

    override fun visitClassOrObject(klass: KtClassOrObject) {
        val innerName = internalClassName(klass)

        fun onClassLike() {
            // Modifiers in java: sealed, [access modifiers], final, static, abstract, non-sealed
            // Type Parameter: Thing<Int, String> <- Int and String
            // Internal name examples:
            // com.tmvkrpxl0.Test -> com/tmvkrpxl0/Test
            // com.tmvkrpxl0.Test.InnerClass -> com/tmvkrpxl0/Test$InnerClass
            klass.modifierList?.accept(this)

            val identifier = klass.nameIdentifier!!
            builder.addClassReference(identifier.startOffset, identifier.textLength, identifier.text, innerName, false)

            klass.typeParameterList?.accept(this)
            klass.superTypeListEntries.visitAll(true)
            klass.body?.accept(this)
        }

        fun onClass() {
            builder.addClassDeclaration(klass.startOffset, klass.textLength, innerName)
            onClassLike()
        }

        fun onInterface() {
            builder.addInterfaceDeclaration(klass.startOffset, klass.textLength, innerName)
            onClassLike()
        }

        fun onEnum() {
            // TODO
        }

        fun onAnnotation() {
            // TODO
        }

        when(klass) {
            is KtClass -> {
                when {
                    klass.isAnnotation() -> onAnnotation()
                    klass.isEnum() -> onEnum()
                    klass.isInterface() -> onInterface()
                    else -> onClass()
                }
            }
            is KtObjectDeclaration -> {
                onClass()
            }
        }

        handled = true
        super.visitClassOrObject(klass)
    }

    fun onField(variable: KtVariableDeclaration) {

    }

    fun onLocal(variable: KtVariableDeclaration) {
        if (localVariableTracker.isEmpty()) localVariableTracker.push(LinkedList())
        val localVars = localVariableTracker.peek()

        localVars += variable

        val method = variable.containingDeclarationForPseudocode as KtFunction
        val methodDescriptor = functionDescriptor(method)

        val classOrFileName = variable.containingClassOrObject?.let { internalClassName(it) }
            ?: internalFileName(variable.containingKtFile)

        if ((method as KtNamed).nameAsName!!.isAnonymous) {
            println("TODO: Implement lambda name resolver")
        } else {
            val typeRef = when {
                variable is KtDestructuringDeclarationEntry -> variable // TODO This should be handled properly
                variable.children[0] is KtCallExpression -> (variable.children[0] as KtCallExpression).calleeExpression // TODO This also should be type reference
                variable.children[0] is KtQualifiedExpression -> (variable.children[0] as KtDotQualifiedExpression).selectorExpression // TODO This also should be type reference
                variable.children[0] is KtConstantExpression -> variable.children[0] // TODO This should be handled properly
                variable.children.size == 2 && variable.children[1] is KtCallExpression -> {
                    variable.children[0] as KtTypeReference
                }
                variable.children.size == 2 && variable.children[1] is KtLambdaExpression -> {
                    variable.children[0] as KtTypeReference
                }
                else -> {
                    error("Unable to parse property")
                }
            }

            builder.addLocalVariableReference(
                variable.startOffset,
                variable.textLength,
                variable.name!!,
                classOrFileName,
                method.name,
                methodDescriptor,
                localVars.lastIndex,
                typeRef.toString() // TODO THIS,
                //element.typeReference!!.name!! // Type was Ljava/util/function/Consumer; or I in the test
            )
        }
    }

    override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
        handled = true

        onLocal(entry)
        super.visitDestructuringDeclarationEntry(entry)
    }

    /**
     * Called when there is field declaration, either var or val of any kind.
     * This includes extension property and destructing, etc...
     * This excludes constructor parameter.
     * There's another interface called `KtValVarKeywordOwner` which can be useful for above purpose.
     * If it's extension variable, for now let's just convert "Class.varName" to "Class$varName"
     * I... really don't think anyone using kotlin would legit use $
     */
    override fun visitProperty(property: KtProperty) {
        handled = true
        val isField = property.isTopLevelKtOrJavaMember() || property.isMember

        if (isField) {
            onField(property)
        } else {
            onLocal(property)
        }
        super.visitProperty(property)
    }

    // In original srg2source when there is method it does nothing and visits children
    // The node is full expression, including variable(or class name if it's static) name
    // When visiting children, thing on the left(class or variable) is recorded first
    // and then the method itself.
    override fun visitCallExpression(expression: KtCallExpression) {
        val calling = bindingContext[BindingContext.CALL, expression.calleeExpression]!!
        // builder.addMethodReference()
    }

    override fun visitElement(element: PsiElement) {
        when(element) {
            is LeafPsiElement, is PsiWhiteSpace, is PsiFile, is KDocElement, is KtBreakExpression -> { handled = true }
        }

        if (!handled) {
            println("UNHANDLED ELEMENT: ${element.javaClass.simpleName}($element ${element.text})")
        }
        handled = false

        if (element is KtClassOrObject) return

        element.acceptChildren(this)
    }

    fun List<PsiElement>.visitAll(allowEmpty: Boolean = false) {
        if (!allowEmpty && isEmpty()) {
            println("EMPTY!")
            Throwable().printStackTrace()
        }
        forEach {
            it.accept(this@KotlinWalker)
        }
    }

    // Get the full binary name, including L; wrappers around class names
    fun getTypeSignature(type: KtTypeReference): String {
        return "L${type.getAbbreviatedTypeOrType(bindingContext)!!.getJetTypeFqName(false).replace('.', '/')};"
    }

    private fun functionDescriptor(element: KtFunction): String {
        require(element is KtNamedFunction || element is KtFunctionLiteral)

        return buildString {
            this.append('(')
            val (values, ret) = if (element is KtFunctionLiteral) {
                val lambdaType = (element.parent.parent as KtCallableDeclaration).typeReference!!.firstChild as KtFunctionType
                lambdaType.parameters.map { it.typeReference!! } to lambdaType.returnTypeReference
            } else {
                element.valueParameters.map { it.typeReference!! } to element.typeReference
            }

            values
        }
    }

    private var handled = false
    private val localVariableTracker = Stack<MutableList<KtVariableDeclaration>>()
    // TODO Find better ways of resolving lambda names
    private val lambdaNames = HashMap<KtLambdaExpression, String>()
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

// In original srg2source, BodyDeclaration is for everything that can have body
// subclass, functions, etc

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