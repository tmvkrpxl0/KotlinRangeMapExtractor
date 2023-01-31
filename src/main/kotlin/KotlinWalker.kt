import net.minecraftforge.srg2source.range.RangeMapBuilder
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.load.kotlin.computeJvmDescriptor
import org.jetbrains.kotlin.load.kotlin.toSourceElement
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.overriddenTreeUniqueAsSequence
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.KotlinType
import java.io.PrintStream
import kotlin.math.absoluteValue

// TODO Deal with constructor, it should account for synthetic params, delegation, etc... so that it 100% matches compiled class
// Although I'm quite unsure why, doesn't it only care what's visible on source?
// TODO Properly compute lambda names
class KotlinWalker(
    private val builder: RangeMapBuilder,
    private val errorLogger: PrintStream,
    result: AnalysisResult
) : KtVisitorVoid() {
    private val bindingContext = result.bindingContext
    private val ignoreChildren = Key<KotlinWalker>("ignoreChildren")
    private val hasRecorded = Key<KotlinWalker>("hasRecorded")
    private val lambdaName = Key<String>("lambdaName")
    private val localVars = Key<List<KtValVarKeywordOwner>>("localVars")

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
        if (!directive.isRoot) {
            directive.putUserData(hasRecorded, this)
            builder.addPackageReference(directive.startOffset, directive.textLength, directive.fqName.asString())
        }
        return super.visitPackageDirective(directive)
    }

    override fun visitParameter(parameter: KtParameter) {
        handled = true

        var earlyReturn = false
        if (parameter.parent !is KtParameterList) earlyReturn = true
        if (parameter.nameIdentifier == null) earlyReturn = true
        if (earlyReturn) return super.visitParameter(parameter)

        val name = parameter.nameIdentifier!!
        val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, parameter]!! as ValueParameterDescriptor
        val methodDescriptor = descriptor.containingDeclaration as FunctionDescriptor

        val container = getContainingClassOrFile(descriptor)
        val method = methodDescriptor.source.getPsi()!! as KtDeclarationWithBody

        builder.addParameterReference(
            name.startOffset,
            name.textLength,
            name.text,
            container,
            method.getNameMaybeLambda(),
            methodDescriptor.computeJvmDescriptor(withName = false),
            parameter.parameterIndex()
        )

        super.visitParameter(parameter)
    }
    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        handled = true
        var earlyReturn = false

        if (expression is KtLabelReferenceExpression) earlyReturn = true
        val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]

        if (descriptor is PackageViewDescriptor) earlyReturn = true
        if (descriptor is PackageFragmentDescriptor) earlyReturn = true
        if (expression is KtOperationReferenceExpression && descriptor == null) {
            println("Ignoring operator expression ${expression.operationSignTokenType} in ${expression.context?.text}")
            earlyReturn = true
        }

        if (earlyReturn) return super.visitSimpleNameExpression(expression)
        descriptor!!

        fun onVariableReference() {
            descriptor as VariableDescriptor

            val container = getContainingClassOrFile(descriptor)
            when (descriptor) {
                is LocalVariableDescriptor, is ValueParameterDescriptor -> {
                    val parent = descriptor.containingDeclaration as FunctionDescriptor
                    val method = (parent.toSourceElement.getPsi()!!) as KtDeclarationWithBody

                    val index = if (descriptor is ValueParameterDescriptor) {
                        descriptor.index
                    } else {
                        val local = descriptor.source.getPsi()!! as KtValVarKeywordOwner
                        val index = method.bodyBlockExpression!!.localVariablesAndParams().indexOf(local)
                        require(index != -1)
                        index
                    }

                    if (descriptor is ValueParameterDescriptor) {
                        builder.addParameterReference(
                            expression.startOffset,
                            expression.textLength,
                            expression.text,
                            container,
                            method.getNameMaybeLambda(),
                            parent.computeJvmDescriptor(withName = false),
                            index
                        )
                    } else {
                        builder.addLocalVariableReference(
                            expression.startOffset,
                            expression.textLength,
                            expression.text,
                            container,
                            method.getNameMaybeLambda(),
                            parent.computeJvmDescriptor(withName = false),
                            index,
                            descriptor.type.classId.jvmName(true)
                        )
                    }
                }

                is PropertyDescriptor -> {
                    builder.addFieldReference(
                        expression.startOffset,
                        expression.textLength,
                        expression.text,
                        container
                    )
                }
                else -> {
                    handled = false
                    errorLogger.println("UNHANDLED NAME EXPRESSION ${expression.text}")
                }
            }
        }

        /**
         * In srg2source, when there is inner class reference, It records multiple class references for each nesting of class
         * and only the most outer one can be non-qualified
         */
        fun onClassReference() {
            descriptor as ClassDescriptor
            if (expression.context is KtSuperExpression) return

            val isQualified = expression.getQualifiedElement() is KtQualifiedExpression

            builder.addClassReference(
                expression.startOffset,
                expression.textLength,
                expression.text,
                descriptor.classId!!.jvmName(),
                isQualified
            )
        }

        /**In original srg2source when there is method it does nothing and visits children
         * The node is full expression, including variable(or class name if it's static) name
         * When visiting children, thing on the left(class or variable) is recorded first
         * and then the method itself.
         *
         * About extension property, I really don't have good idea how to deal with them.
         * For now I'll put receiver class name in front of owner name
         */
        fun onFunctionReference() {
            descriptor as FunctionDescriptor

            val receiver = expression.getQualifiedExpressionForSelector()?.receiverExpression
            val container = getContainingClassOrFile(descriptor, true)

            var origin = descriptor.name.asString()

            if (receiver != null) {
                descriptor.extensionReceiverParameter?.let {
                    origin = "${it.type.classId.jvmName()}\$$origin"
                }
            }

            if (descriptor is ConstructorDescriptor) {
                val reference = expression.getQualifiedElement()
                val isQualified = reference is KtQualifiedExpression

                val start = reference.startOffset
                val end = expression.endOffset
                val length = end - start
                val text = expression.containingKtFile.text.substring(start, end)
                builder.addClassReference(
                    start,
                    length,
                    text,
                    descriptor.constructedClass.classId!!.jvmName(),
                    isQualified
                )
            } else {
                builder.addMethodReference(
                    expression.startOffset,
                    expression.textLength,
                    expression.text,
                    container,
                    descriptor.name.asString(),
                    descriptor.computeJvmDescriptor(withName = false)
                )
            }

        }

        when (descriptor) {
            is VariableDescriptor -> onVariableReference()
            is ClassDescriptor -> onClassReference()
            is FunctionDescriptor -> onFunctionReference()
        }

        super.visitSimpleNameExpression(expression)
    }

    /**
     * In original srg2source, it tracks all synthetic parameters, such as string parameter for enum class
     * And then it visits:
     * * Javadoc // although probably ignored
     * * Modifiers such as public
     * * Type Parameters
     * * Return type
     * * Name
     * * Receiver Type
     * * Receiver Qualifier
     * ```java
     * public class Parent {
     *     public class Child {
     *         public Child(Parent/*<- Receiver Type*/ Parent.this /*<- Receiver Qualifier(without this)*/) {
     *         }
     *     }
     * }
     * ```
     * * Parameters
     * * Extra dimensions - `@NotNull int @NotNull [] @NotNull[] a` <- to record all annotations in this
     * * Thrown exceptions
     * * Body
     *
     * and then doesn't visit children
     */
    override fun visitNamedFunction(function: KtNamedFunction) {
        val descriptor = bindingContext[BindingContext.FUNCTION, function]!!
        val owner = getContainingClassOrFile(descriptor, true)
        builder.addMethodDeclaration(
            function.startOffset,
            function.textLength,
            function.name!!,
            descriptor.computeJvmDescriptor(withName = false)
        )

        function.docComment?.accept(this)
        function.modifierList?.accept(this)
        function.typeParameters.visitAll(true)
        function.typeReference?.accept(this)

        val methodName = function.nameIdentifier!!
        builder.addMethodReference(
            methodName.startOffset,
            methodName.textLength,
            methodName.text,
            owner,
            descriptor.name.asString(),
            descriptor.computeJvmDescriptor(withName = false)
        )

        // Kotlin does not have java's receiver type thing
        // function.receiverTypeReference does exist, but it's not same thing
        // same goes for receiver qualifier

        function.valueParameters.visitAll(true)
        // kotlin doesn't put annotation on extra dimension
        // for thrown exceptions, @Throws would do
        function.bodyExpression?.accept(this)

        val leftOver = function.children.toMutableList()
        leftOver.remove(function.docComment)
        leftOver.remove(function.modifierList)
        leftOver.remove(function.typeParameterList)
        leftOver.remove(function.typeReference)
        leftOver.remove(function.nameIdentifier)
        leftOver.remove(function.receiverTypeReference)
        leftOver.remove(function.getQualifiedExpressionForReceiver())
        leftOver.remove(function.valueParameterList)
        leftOver.remove(function.bodyExpression)

        if (leftOver.isNotEmpty()) {
            println("named function leftOver: $leftOver")
        }

        function.putUserData(ignoreChildren, this)
        function.putUserData(hasRecorded, this)

        handled = true
        super.visitNamedFunction(function)
    }

    /**
     * This currently does not handle synthetic params
     */
    override fun visitEnumEntry(enumEntry: KtEnumEntry) {
        handled = true
        val enumClass = enumEntry.containingClass()!!
        val name = enumEntry.nameIdentifier!!
        builder.addFieldReference(name.startOffset, name.textLength, name.text, enumClass.getClassId()!!.jvmName())

        if (enumEntry.body == null) return super.visitEnumEntry(enumEntry)

        super.visitEnumEntry(enumEntry)
    }

    override fun visitLambdaExpression(lambda: KtLambdaExpression) {
        val descriptor = bindingContext[BindingContext.FUNCTION, lambda.functionLiteral]!!
        val name = lambda.putUserDataIfAbsent(lambdaName, lambda.getJvmName())

        builder.addMethodDeclaration(
            lambda.startOffset,
            lambda.textLength,
            name,
            descriptor.computeJvmDescriptor(withName = false)
        )

        handled = true
        lambda.valueParameters.visitAll(true)
        lambda.bodyExpression!!.accept(this)

        val leftOver = lambda.functionLiteral.children.toMutableList()
        leftOver.remove(lambda.functionLiteral.valueParameterList)
        leftOver.remove(lambda.bodyExpression)

        if (leftOver.isNotEmpty()) println("Unvisited: $leftOver")
    }

    /**
     * When it defines one of: class, object, interface, enum, or annotation
     * It's not called for references
     */
    override fun visitClassOrObject(klass: KtClassOrObject) {
        if (klass is KtEnumEntry) return super.visitClassOrObject(klass)

        val innerName = klass.getClassId()!!.jvmName()

        fun onClassLike() {
            // Modifiers in java: sealed, [access modifiers], final, static, abstract, non-sealed
            // Type Parameter: Thing<Int, String> <- Int and String
            // Internal name examples:
            // com.tmvkrpxl0.Test -> com/tmvkrpxl0/Test
            // com.tmvkrpxl0.Test.InnerClass -> com/tmvkrpxl0/Test$InnerClass

            // Order in the original srg2source:
            // Modifier
            // Name
            // Type Params
            // Super Class Type
            // Super Interface Types
            // body

            klass.docComment?.accept(this)
            klass.modifierList?.accept(this)

            klass.nameIdentifier?.let {
                builder.addClassReference(
                    it.startOffset,
                    it.textLength,
                    it.text,
                    innerName,
                    it is KtQualifiedExpression
                )
            }

            klass.typeParameters.visitAll(true)
            klass.superTypeListEntries.visitAll(true)
            klass.primaryConstructor?.accept(this)
            klass.body?.accept(this)

            val leftOver = klass.children.toMutableList()

            leftOver.remove(klass.docComment)
            leftOver.remove(klass.modifierList)
            leftOver.remove(klass.typeParameterList)
            leftOver.remove(klass.getSuperTypeList())
            leftOver.remove(klass.primaryConstructor)
            leftOver.remove(klass.body)
            if (leftOver.isNotEmpty()) {
                errorLogger.println("Warning: unvisited $leftOver")
            }
        }

        fun onClass() {
            if (!(klass is KtObjectDeclaration && klass.isCompanion() && klass.nameIdentifier == null)) {
                builder.addClassDeclaration(klass.startOffset, klass.textLength, innerName)
            }
            onClassLike()
        }

        fun onInterface() {
            builder.addInterfaceDeclaration(klass.startOffset, klass.textLength, innerName)
            onClassLike()
        }

        fun onEnum() {
            builder.addEnumDeclaration(klass.startOffset, klass.textLength, innerName)
            // Order in srg2source:
            // Javadoc
            // Modifier
            // Name
            // Super Interface Types
            // Enum Constants
            // Body Declaration

            // In kotlin, there's Enum class that looks like this:
            // enum class EnumTest {
            //    A {
            //      fun run() {
            //
            //      }
            //      },
            //    B {
            //
            //      },
            //    C {
            //
            //    }
            //}
            // A here is entirely new class and I need to handle that too

            klass.docComment?.accept(this)
            klass.modifierList?.accept(this)

            klass.nameIdentifier?.let {
                builder.addClassReference(
                    it.startOffset,
                    it.textLength,
                    it.text,
                    innerName,
                    it is KtQualifiedExpression
                )
            }

            klass.superTypeListEntries.visitAll(true)

            // Could this cause problem? primary constructor is visited before body
            klass.primaryConstructor?.accept(this)
            klass.body?.accept(this)

            val leftOver = klass.children.toMutableList()

            leftOver.remove(klass.docComment)
            leftOver.remove(klass.modifierList)
            leftOver.remove(klass.getSuperTypeList())
            leftOver.remove(klass.primaryConstructor)
            leftOver.remove(klass.body)
            if (leftOver.isNotEmpty()) {
                errorLogger.println("Warning: unvisited $leftOver")
            }
        }

        fun onAnnotation() {
            builder.addAnnotationDeclaration(
                klass.startOffset,
                klass.textLength,
                klass.name!! /* reproduce original behaviour. I feel like it should also include package names though*/
            )

            handled = true
        }

        when (klass) {
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

            else -> {
                throw IllegalStateException("Unable to determine kind of class!")
            }
        }

        handled = true
        klass.putUserData(ignoreChildren, this)
        super.visitClassOrObject(klass)
    }

    fun onField(variable: KtVariableDeclaration) {
        val descriptor = bindingContext[BindingContext.VARIABLE, variable]!!
        val container = getContainingClassOrFile(descriptor)

        val fieldName = variable.nameIdentifier!!
        builder.addFieldReference(fieldName.startOffset, fieldName.textLength, fieldName.text, container)
    }

    fun onLocal(variable: KtVariableDeclaration) {
        val method =
            generateSequence<KtElement>(variable) { it.parent as KtElement }.find { it is KtFunction }!! as KtFunction
        val methodDescriptor = bindingContext[BindingContext.FUNCTION, method]!!

        val container = getContainingClassOrFile(methodDescriptor)

        val varDescriptor = bindingContext[BindingContext.VARIABLE, variable]!!
        val varName = variable.nameIdentifier!!

        val localVars = method.bodyBlockExpression!!.localVariablesAndParams()


        val index = localVars.indexOf(variable)

        builder.addLocalVariableReference(
            varName.startOffset,
            varName.textLength,
            varName.text,
            container,
            method.getNameMaybeLambda(),
            methodDescriptor.computeJvmDescriptor(withName = false),
            index,
            varDescriptor.type.classId.jvmName(true)
        )
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

        if (property.isLocal) {
            onLocal(property)
        } else {
            onField(property)
        }
        super.visitProperty(property)
    }

    override fun visitElement(element: PsiElement) {

        when (element) {
            is LeafPsiElement, is PsiWhiteSpace, is PsiFile, is KDocElement, is KtBreakExpression, is KtConstantExpression,
            is KtBlockExpression, is KtFileAnnotationList, is KtAnnotationEntry, is KtConstructorCalleeExpression,
            is KtAnnotationUseSiteTarget, is KtValueArgumentList, is KtValueArgument, is KtStringTemplateEntry,
            is KtStringTemplateExpression, is KtTypeReference, is KtUserType, is KtQualifiedExpression,
            is KtFunctionType, is KtParameterList, is KtTypeParameter, is KtModifierList,
            is KtClassBody, is KtPrimaryConstructor, is KtDelegatedSuperTypeEntry, is KtCallExpression,
            is KtBinaryExpression, is KtContainerNode, is KtReturnExpression, is KtForExpression,
            is KtDestructuringDeclaration, is KtSuperExpression, is KtSuperTypeCallEntry,
            is KtClassLiteralExpression -> {
                handled = true
            }

            is KtImportList, is KtImportDirective /* Import is not handled in srg2source */ -> {
                element.putUserData(ignoreChildren, this)
                handled = true
            }
        }

        if (!handled) {
            println("UNHANDLED ELEMENT: ${element.javaClass.simpleName}($element ${element.text})")
        } else {
            println("HANDLED ELEMENT: ${element.javaClass.simpleName}($element ${element.text})")
        }
        handled = false

        if (element.getUserData(ignoreChildren) == this) return
        element.acceptChildren(this)
    }

    fun List<PsiElement>.visitAll(allowEmpty: Boolean = false) {
        if (!allowEmpty && isEmpty()) {
            println("EMPTY!")
            Throwable().printStackTrace(errorLogger)
        }
        forEach {
            it.accept(this@KotlinWalker)
        }
    }

    fun KtLambdaExpression.getJvmName(): String {
        val trace = generateSequence<KtElement>(this) {
            if (it is KtFile) return@generateSequence null
            it.parent as KtElement
        }.map { it.text.hashCode() }
        val number = trace.reduce { acc, it ->
            acc * 31 + it
        }.absoluteValue
        return "lambda\$$number"
    }

    @Suppress("NAME_SHADOWING")
    fun getContainingClassOrFile(descriptor: CallableDescriptor, useRoot: Boolean = false): String {
        val descriptor = if (useRoot) {
            descriptor.overriddenTreeUniqueAsSequence(false).last()
        } else {
            descriptor
        }
        val sequence = generateSequence(descriptor as DeclarationDescriptor) { it.containingDeclaration }
        val container = sequence.find { it is ClassOrPackageFragmentDescriptor }!!

        return when (container) {
            is PackageFragmentDescriptor -> {
                if (container.fqName.isRoot) {
                    "<root>"
                } else {
                    println("source: ${descriptor}")
                    container.fqName.joinSlash().plus("/<top-level>")
                }
            }

            is ClassDescriptor -> {
                container.classId!!.jvmName()
            }

            else -> {
                throw IllegalStateException("Unable to parse owner!")
            }
        }
    }

    fun FqName.joinSlash() = pathSegments().joinToString("/")

    val KotlinType.classId: ClassId
        get() {
            val declaration = constructor.declarationDescriptor!! as ClassDescriptor
            return ClassId(
                declaration.containingPackage()!!,
                declaration.fqNameSafe,
                declaration.visibility.delegate == Visibilities.Local
            )
        }

    private fun KtDeclarationWithBody.getNameMaybeLambda(): String {
        return when(this) {
            is KtFunction -> {
                if (nameAsName!!.isAnonymous) {
                    var lambdaName = parent.getUserData(lambdaName)
                    return if (lambdaName == null) {
                        lambdaName = (parent as KtLambdaExpression).getJvmName()
                        parent.putUserData(this@KotlinWalker.lambdaName, lambdaName)
                        lambdaName
                    } else lambdaName
                } else name!!
            }
            is KtPropertyAccessor -> {
                namePlaceholder.text
            }
            else -> {
                throw IllegalStateException("Unknown declaration type $text (${this::class.qualifiedName}")
            }
        }
    }

    private fun ClassId.jvmName(isBinaryName: Boolean = false): String {
        val relativePath = buildString {
            relativeClassName.pathSegments().forEachIndexed { index, name ->
                if (index != 0) append('$')
                append(name.asString())
            }
        }
        val toReturn = if (packageFqName.isRoot) {
            relativePath
        }else {
            packageFqName.joinSlash() + "/$relativePath"
        }

        return if (isBinaryName) {
            "L$toReturn;"
        } else {
            toReturn
        }
    }

    fun KtBlockExpression.localVariablesAndParams(): List<KtValVarKeywordOwner> {
        if (getUserData(localVars) != null) return getUserData(localVars)!!

        require(parent is KtFunction)
        val toReturn = mutableListOf<KtValVarKeywordOwner>()
        val visitor = object: KtVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                if (element !is KtLambdaExpression) element.acceptChildren(this)
            }

            override fun visitDeclaration(dcl: KtDeclaration) {
                if (dcl is KtValVarKeywordOwner) {
                    toReturn += dcl
                }
                super.visitDeclaration(dcl)
            }
        }
        parent.accept(visitor)

        putUserData(localVars, toReturn)
        return toReturn
    }

    private var handled = false
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
// Well it's not an important element of range map, so it's fine to not define another entry

// In original srg2source, BodyDeclaration is for everything that can have body
// subclass, functions, etc
