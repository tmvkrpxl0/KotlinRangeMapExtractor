import com.intellij.codeWithMe.getStackTrace
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import net.minecraftforge.srg2source.range.RangeMapBuilder
import org.jetbrains.kotlin.analyzer.ResolverForModule
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isSuspendFunctionType
import org.jetbrains.kotlin.cfg.containingDeclarationForPseudocode
import org.jetbrains.kotlin.cfg.getDeclarationDescriptorIncludingConstructors
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.load.kotlin.computeJvmDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getAnnotationEntries
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.jvm.JvmBindingContextSlices
import org.jetbrains.kotlin.resolve.lazy.LazyDeclarationResolver
import org.jetbrains.kotlin.resolve.lazy.LocalDescriptorResolver
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.scopes.ScopeUtils
import org.jetbrains.kotlin.resolve.source.toSourceElement
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils.NO_EXPECTED_TYPE
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.types.expressions.ExpressionTypingVisitor
import org.jetbrains.kotlin.types.expressions.ExpressionTypingVisitorDispatcher
import java.util.*
import kotlin.random.Random
import kotlin.random.nextInt

// TODO Figure out how to resolve origin of givin element
class KotlinWalker(private val builder: RangeMapBuilder, serviceResolver: ResolverForModule): KtVisitorVoid() {
    val analyzer = serviceResolver.componentProvider.get<LazyTopDownAnalyzer>()
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
    val annotations = serviceResolver.componentProvider.get<AnnotationResolver>()


    override fun visitBreakExpression(expression: KtBreakExpression) {
        println("Break: ${expression.children}")

        handled = true
        return super.visitBreakExpression(expression)
    }

    override fun visitPackageDirective(directive: KtPackageDirective) {
        handled = true
        if (!directive.isRoot) builder.addPackageReference(directive.startOffset, directive.textLength, directive.fqName.asString())
        return super.visitPackageDirective(directive)
    }

    override fun visitLambdaExpression(lambda: KtLambdaExpression) {
        val name = "lambda${Random.nextInt(0..9999)}"
        lambdaNames[lambda] = name

        // val descriptor = functions.resolveFunctionExpressionDescriptor()

        // builder.addMethodDeclaration(lambda.startOffset, lambda.textLength, name)

        handled = true
        lambda.valueParameters.visitAll(true)
        lambda.bodyExpression!!.accept(this)

        super.visitLambdaExpression(lambda)
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

        val scope = bindingTrace.bindingContext.get(BindingContext.LEXICAL_SCOPE, method)!!

        types.resolveType(scope, method.typeReference!!, bindingTrace, true)
        when(method) {
            is KtNamedFunction -> {
                functions.resolveFunctionDescriptor(scope.ownerDescriptor, scope, method, bindingTrace, DataFlowInfo.EMPTY, null)
            }
            is KtFunctionLiteral -> {
                val descriptor = AnonymousFunctionDescriptor(
                    scope.ownerDescriptor,
                    annotations.resolveAnnotationsWithArguments(scope, method.getAnnotationEntries(), bindingTrace),
                    CallableMemberDescriptor.Kind.DECLARATION, method.toSourceElement(),
                    NO_EXPECTED_TYPE.isSuspendFunctionType
                )
            }
            else -> throw IllegalStateException("Unable to determine type of a method")
        }
        val methodDescription = method.getDeclarationDescriptorIncludingConstructors(bindingTrace.bindingContext) as FunctionDescriptor

        val classOrFileName = variable.containingClassOrObject?.let { internalClassName(it) }
            ?: internalFileName(variable.containingKtFile)

        if ((method as KtNamed).nameAsName!!.isAnonymous) {
            println("TODO: Implement lambda name resolver")
        } else {
            /*builder.addLocalVariableReference(
                variable.startOffset,
                variable.textLength,
                variable.name!!,
                classOrFileName,
                method.name,
                methodDescription.computeJvmDescriptor(),
                localVars.lastIndex,
                getTypeSignature(variableDescriptor.type),
                //element.typeReference!!.name!! // Type was Ljava/util/function/Consumer; or I in the test
            )*/
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
        val owner = element.calleeExpression!!
    }

    override fun visitElement(element: PsiElement) {
        when(element) {
            is LeafPsiElement, is PsiWhiteSpace, is PsiFile, is KDocElement -> { handled = true }
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
            println(getStackTrace())
        }
        forEach {
            it.accept(this@KotlinWalker)
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