import net.minecraftforge.srg2source.range.RangeMapBuilder
import net.minecraftforge.srg2source.util.io.ConfLogger
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.isAnonymous
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.SyntheticFieldDescriptor
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.kdoc.psi.impl.KDocLink
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.descriptors.JavaClassConstructorDescriptor
import org.jetbrains.kotlin.load.java.descriptors.getImplClassNameForDeserialized
import org.jetbrains.kotlin.load.kotlin.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.psi.stubs.elements.KtNameReferenceExpressionElementType
import org.jetbrains.kotlin.psi.synthetics.findClassDescriptor
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.sam.SamConstructorDescriptor
import org.jetbrains.kotlin.resolve.source.PsiSourceFile
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.error.ErrorClassDescriptor
import org.jetbrains.kotlin.types.typeUtil.*
import kotlin.math.absoluteValue


class KotlinWalker(
    private val builder: RangeMapBuilder,
    private val logger: ConfLogger<*>,
    result: AnalysisResult,
    private val compatibility: Boolean
) : KtVisitorVoid() {
    private val bindingContext = result.bindingContext
    private val ignoreChildren = Key<KotlinWalker>("ignoreChildren")
    private val lambdaName = Key<String>("lambdaName")
    private val localVarParams = Key<List<PsiElement>>("localVars")

    override fun visitPackageDirective(directive: KtPackageDirective) {
        handled = true
        if (!directive.isRoot) {
            val name = unquote(directive.packageNameExpression!!)
            builder.addPackageReference(name.startOffset, name.textLength, name.text)
        }
        return super.visitPackageDirective(directive)
    }

    override fun visitParameter(parameter: KtParameter) {
        handled = true

        var earlyReturn = false
        if (parameter.parent !is KtParameterList) earlyReturn = true
        if (parameter.nameIdentifier == null) earlyReturn = true
        if (earlyReturn) return super.visitParameter(parameter)

        val name = unquote(parameter.nameIdentifier!!)
        val descriptor =
            bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, parameter]!! as DeclarationDescriptorWithSource
        val methodDescriptor = descriptor.containingDeclaration as FunctionDescriptor

        val container = trimTrace { getContainerInternalName(descriptor) } ?: return
        val method = methodDescriptor.source.getPsi()!! as KtDeclarationWithBody

        builder.addParameterReference(
            name.startOffset,
            name.textLength,
            name.text,
            container,
            method.getNameMaybeLambda(methodDescriptor),
            methodDescriptor.computeJvmDescriptor(withName = false),
            parameter.parameterIndex()
        )

        super.visitParameter(parameter)
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        handled = true
        var earlyReturn = false

        if (expression.text.isEmpty()) earlyReturn = true
        if (expression is KtLabelReferenceExpression) earlyReturn = true
        val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, expression]?.original

        if (descriptor is PackageViewDescriptor) earlyReturn = true
        if (descriptor is PackageFragmentDescriptor) earlyReturn = true
        if (descriptor is SyntheticFieldDescriptor) earlyReturn = true
        if (expression.context is KtThisExpression) earlyReturn = true
        if (expression is KtOperationReferenceExpression && descriptor == null) {
            earlyReturn = true
        }

        if (earlyReturn) return super.visitSimpleNameExpression(expression)
        if (descriptor == null) {
            logger.error("Unresolved expression: ${expression.text} at ${expression.startOffset} in ${expression.containingKtFile.name}")
            return
        }
        val unquoted = unquote(expression)

        fun onVariableReference() {
            descriptor as VariableDescriptor

            val container = trimTrace { getContainerInternalName(descriptor) } ?: return

            when (descriptor) {
                is LocalVariableDescriptor -> {
                    val local = descriptor.source.getPsi()!! as KtValVarKeywordOwner
                    val method = local.parents.first { it is KtFunction || it is KtClassInitializer }

                    val arguments =
                        if (method is KtFunction) {
                            val methodDescriptor = method.descriptor

                            val varsParams = method.bodyBlockExpression!!.localVariablesAndParams(methodDescriptor)
                            val index = varsParams.indexOf(local)
                            val methodName = method.getNameMaybeLambda(methodDescriptor)
                            VariableReferenceArguments(
                                container,
                                methodName,
                                methodDescriptor.computeJvmDescriptor(withName = false),
                                index
                            )
                        } else {
                            method as KtClassInitializer
                            val index = method.localVariablesAndParams().indexOf(local)
                            val primaryDescriptor = method
                                .containingClass()
                                ?.primaryConstructor
                                ?.descriptor
                                ?.computeJvmDescriptor(withName = false) ?: "()V"

                            VariableReferenceArguments(
                                container,
                                "<init>",
                                primaryDescriptor,
                                index
                            )
                        }

                    builder.addLocalVariableReference(
                        unquoted.startOffset,
                        unquoted.textLength,
                        unquoted.text,
                        arguments.methodContainer,
                        arguments.methodName,
                        arguments.methodDescriptor,
                        arguments.varIndex,
                        trimTrace { descriptor.type.classId.jvmName } ?: return
                    )
                }

                is ValueParameterDescriptor -> {
                    val methodDescriptor = descriptor.containingDeclaration as FunctionDescriptor
                    if (methodDescriptor is JavaClassConstructorDescriptor && methodDescriptor.isAnnotationConstructor()) {
                        builder.addMethodReference(
                            unquoted.startOffset,
                            unquoted.textLength,
                            unquoted.text,
                            container,
                            descriptor.name.asString(),
                            "()L" + descriptor.type.classId.internalName + ";"
                        )
                    } else {
                        val parentName = if (methodDescriptor is JavaClassConstructorDescriptor) {
                            methodDescriptor.name.asString()
                        } else {
                            val parentMethod = (methodDescriptor.source.getPsi()!!) as KtDeclarationWithBody
                            parentMethod.getNameMaybeLambda(methodDescriptor)
                        }

                        val index = descriptor.index

                        builder.addParameterReference(
                            unquoted.startOffset,
                            unquoted.textLength,
                            unquoted.text,
                            container,
                            parentName,
                            methodDescriptor.computeJvmDescriptor(withName = false),
                            index
                        )
                    }
                }

                is SyntheticJavaPropertyDescriptor -> {
                    // Only direct assignment will solely reference setter, any other operations that may invoke setter
                    // will also invoke getter

                    // Currently, it's not possible to make applier respect synthetic java property access
                    // So remapped code will not compile, this is just to give them hint on what renamed getter/setter should be
                    val isSetter = (expression.parent as? KtUnaryExpression)?.operationToken == KtTokens.EQ
                    val targetMethod = (if (isSetter) descriptor.setMethod!! else descriptor.getMethod).findSuperRoot() as FunctionDescriptor

                    val name = targetMethod.name.asString()
                    val javaDescriptor = targetMethod.computeJvmDescriptor(withName = false)
                    builder.addMethodReference(
                        unquoted.startOffset,
                        unquoted.textLength,
                        unquoted.text,
                        getContainerInternalName(targetMethod),
                        name,
                        javaDescriptor
                    )
                }

                is PropertyDescriptor -> {
                    builder.addFieldReference(
                        unquoted.startOffset,
                        unquoted.textLength,
                        unquoted.text,
                        trimTrace { getContainerInternalName(descriptor.findSuperRoot()) } ?: return
                    )
                }

                else -> {
                    handled = false
                    logger.logger.println("UNHANDLED NAME EXPRESSION ${expression.text}")
                }
            }
        }

        /**
         * In srg2source, when there is inner class reference, It records multiple class references for each nesting of class
         * and only the most outer one can be non-qualified
         */
        fun onClassReference() {
            descriptor as ClassDescriptor
            if (descriptor is ErrorClassDescriptor) {
                throw RuntimeException("Unable to resolve referenced class of $expression at ${expression.startOffset} in ${expression.containingKtFile.name}")
            }

            if (expression.context is KtSuperExpression) return
            if (expression.context is KtThisExpression) return

            val reference = expression.getQualifiedElement()

            val isQualified = (reference as? KtUserType)?.qualifier != null || reference is KtDotQualifiedExpression
            // Why would anyone name companion object same as class that containing it?
            val isImplicitCompanionReference = descriptor.isCompanionObject && descriptor.name != expression.getReferencedNameAsName()
            val classDescriptor = if (compatibility && isImplicitCompanionReference) {
                (descriptor.containingDeclaration as ClassDescriptor).classId!!.jvmName
            } else {
                descriptor.classId!!.jvmName
            }

            var start = if (isQualified && descriptor.parents.firstOrNull() !is ClassDescriptor) reference.startOffset else expression.startOffset
            if (expression.containingKtFile.text[start] == '`') start++
            val end = expression.endOffset
            val text = expression.containingKtFile.text.substring(start, end).replace("`", "")
            if (text.contains(" ")) throw IllegalStateException("Element containing space is unsupported!")

            builder.addClassReference(
                start,
                text.length,
                text,
                classDescriptor,
                isQualified
            )
        }

        fun onFunctionReference() {
            descriptor as FunctionDescriptor
            if (descriptor.isOperator && expression.context is KtOperationExpression) return

            val root = descriptor.findSuperRoot() as FunctionDescriptor
            val container = trimTrace {  getContainerInternalName(root) }

            if (descriptor is ConstructorDescriptor) {
                val reference = expression.getQualifiedElement()
                val isQualified = (reference as? KtUserType)?.qualifier != null || reference is KtQualifiedExpression

                var start = if (isQualified && descriptor.constructedClass.parents.firstOrNull() !is ClassDescriptor) reference.startOffset else expression.startOffset
                if (expression.containingKtFile.text[start] == '`') start++
                val end = expression.endOffset
                val text = expression.containingKtFile.text.substring(start, end).replace("`", "")
                builder.addClassReference(
                    start,
                    text.length,
                    text,
                    descriptor.constructedClass.classId!!.jvmName,
                    isQualified
                )
            } else {
                builder.addMethodReference(
                    unquoted.startOffset,
                    unquoted.textLength,
                    unquoted.text,
                    container,
                    descriptor.name.asString(),
                    root.computeJvmDescriptor(withName = false)
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
        val descriptor = bindingContext[BindingContext.FUNCTION, function]!!.findSuperRoot() as FunctionDescriptor
        val container = trimTrace { getContainerInternalName(descriptor) }

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

        val methodName = unquote(function.nameIdentifier!!)
        builder.addMethodReference(
            methodName.startOffset,
            methodName.textLength,
            methodName.text,
            container,
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

        handled = true
        super.visitNamedFunction(function)
    }

    override fun visitEnumEntry(enumEntry: KtEnumEntry) {
        handled = true
        val enumClass = enumEntry.containingClass()!!
        val name = unquote(enumEntry.nameIdentifier!!)
        builder.addFieldReference(name.startOffset, name.textLength, name.text, enumClass.getClassId()!!.jvmName)

        if (enumEntry.body == null) return super.visitEnumEntry(enumEntry)

        super.visitEnumEntry(enumEntry)
    }

    override fun visitLambdaExpression(lambda: KtLambdaExpression) {
        val descriptor = bindingContext[BindingContext.FUNCTION, lambda.functionLiteral]!!
        val name = lambda.putUserDataIfAbsent(lambdaName, lambda.generateJvmName())

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

        val innerName = if (klass.getClassId() != null) {
            klass.getClassId()!!.jvmName
        } else {
            getContainerInternalName(klass.findClassDescriptor(bindingContext)) + "\$${klass.text.hashCode()}"
        }

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
                val unquoted = unquote(it)
                builder.addClassReference(
                    unquoted.startOffset,
                    unquoted.textLength,
                    unquoted.text,
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
                logger.logger.println("Warning: unvisited $leftOver")
            }
        }

        fun onClass() {
            // If it is not unnamed companion object, add declaration
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
                val unquoted = unquote(it)
                builder.addClassReference(
                    unquoted.startOffset,
                    unquoted.textLength,
                    unquoted.text,
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
                logger.logger.println("Warning: unvisited $leftOver")
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
                throw RuntimeException("Unable to determine kind of class!")
            }
        }

        handled = true
        klass.putUserData(ignoreChildren, this)
        super.visitClassOrObject(klass)
    }

    private fun onField(variable: KtVariableDeclaration) {
        val descriptor = bindingContext[BindingContext.VARIABLE, variable]!!.original
        val container = trimTrace { getContainerInternalName(descriptor) } ?: return

        val fieldName = unquote(variable.nameIdentifier!!)
        builder.addFieldReference(fieldName.startOffset, fieldName.textLength, fieldName.text, container)
    }

    private fun onLocal(variable: KtVariableDeclaration) {
        val method = variable.parents.first { it is KtFunction || it is KtClassInitializer }
        val varDescriptor = bindingContext[BindingContext.VARIABLE, variable]!!.original as VariableDescriptor
        val varName = unquote(variable.nameIdentifier!!)
        val type = trimTrace { varDescriptor.type.classId.jvmName } ?: return

        val (container, name, descriptor, index) = if (method is KtClassInitializer) {
            val result = method.analyze(variable)

            VariableReferenceArguments(
                result.container.classId!!.jvmName,
                "<init>",
                result.descriptor,
                result.varIndex
            )
        } else {
            method as KtFunction

            val methodDescriptor = method.descriptor

            val localVars = method.bodyBlockExpression!!.localVariablesAndParams(methodDescriptor as CallableDescriptor)

            VariableReferenceArguments(
                getContainerInternalName(methodDescriptor),
                method.getNameMaybeLambda(methodDescriptor as CallableDescriptor),
                methodDescriptor.computeJvmDescriptor(withName = false),
                localVars.indexOf(variable)
            )
        }

        builder.addLocalVariableReference(
            varName.startOffset,
            varName.textLength,
            varName.text,
            container,
            name,
            descriptor,
            index,
            type
        )
    }

    override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
        handled = true
        if (compatibility) return super.visitPropertyAccessor(accessor)

        val descriptor = bindingContext[BindingContext.PROPERTY_ACCESSOR, accessor]!!.original
        val container = getContainerInternalName(descriptor)

        builder.addMethodDeclaration(
            accessor.startOffset,
            accessor.textLength,
            accessor.getNameMaybeLambda(descriptor),
            descriptor.computeJvmDescriptor(withName = false)
        )

        val unquoted = unquote(accessor.namePlaceholder)
        builder.addMethodReference(
            unquoted.startOffset,
            unquoted.textLength,
            unquoted.text,
            container,
            accessor.getNameMaybeLambda(descriptor),
            descriptor.computeJvmDescriptor(withName = false)
        )

        super.visitPropertyAccessor(accessor)
    }

    override fun visitClassInitializer(initializer: KtClassInitializer) {
        handled = true
        if (compatibility) return super.visitClassInitializer(initializer)

        val container = initializer.containingDeclaration

        val descriptor = container.primaryConstructor?.let {
            val function = bindingContext[BindingContext.CONSTRUCTOR, it]!!.original
            function.computeJvmDescriptor(withName = false)
        } ?: "()V"

        builder.addMethodDeclaration(
            initializer.startOffset,
            initializer.textLength,
            "<init>",
            descriptor
        )

        builder.addMethodReference(
            initializer.startOffset,
            4,
            "init",
            container.getClassId()!!.jvmName,
            "<init>",
            descriptor
        )
        super.visitClassInitializer(initializer)
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
            is KtClassBody, is KtConstructor<*>, is KtDelegatedSuperTypeEntry, is KtCallExpression,
            is KtOperationExpression, is KtContainerNode, is KtReturnExpression, is KtForExpression,
            is KtDestructuringDeclaration, is KtSuperExpression, is KtSuperTypeCallEntry,
            is KtClassLiteralExpression, is KtInitializerList, is KtScriptInitializer, is KtCallableReferenceExpression,
            is KtTypeProjection, is KtTypeArgumentList, is KtIfExpression, is KtThisExpression,
            is KtArrayAccessExpression, is KtParenthesizedExpression, is KtConstructorDelegationCall,
            is KtWhenCondition, is KtWhenExpression, is KtWhenEntry, is KtThrowExpression,
            is KtCollectionLiteralExpression, is KtValueArgumentName, is KtPropertyDelegate,
            is KtNullableType, is KtSuperTypeEntry, is KtObjectLiteralExpression,
            is KDocLink, is KDocName, is KtConstructorDelegationReferenceExpression,
            is KtContinueExpression, is KtWhileExpressionBase,
            -> {
                handled = true
            }

            is KtImportList, is KtImportDirective /* Import is not handled in srg2source */ -> {
                element.putUserData(ignoreChildren, this)
                handled = true
            }
        }

        if (!handled) {
            println("UNHANDLED ELEMENT: ${element.javaClass.simpleName}($element ${element.text})")
        }
        handled = false

        if (element.getUserData(ignoreChildren) == this) return
        element.acceptChildren(this)
    }
    
    private val ClassId.jvmName: String
        get() {
            val packageName = packageFqName.joinSlash()
            val classPath = relativeClassName.pathSegments().joinToString("$")
            return "$packageName/$classPath"
        }

    private fun List<PsiElement>.visitAll(allowEmpty: Boolean = false) {
        if (!allowEmpty && isEmpty()) {
            println("EMPTY!")
            Throwable().printStackTrace(logger.logger)
        }
        forEach {
            it.accept(this@KotlinWalker)
        }
    }

    data class InitializerInfo(
        val container: ClassDescriptor,
        val descriptor: String,
        val varIndex: Int
    )

    private fun KtClassInitializer.analyze(variable: KtValVarKeywordOwner): InitializerInfo {
        val container = containingClassOrObject!!.findClassDescriptor(bindingContext)
        val descriptor = containingClass()
            ?.primaryConstructor
            ?.let { bindingContext[BindingContext.CONSTRUCTOR, it]!!.original.computeJvmDescriptor(withName = false) }
            ?: "()V"
        val variables = localVariablesAndParams()
        val index = variables.indexOf(variable)
        require(index != -1)
        return InitializerInfo(container, descriptor, index)
    }

    private fun KtLambdaExpression.generateJvmName(): String {
        val trace = generateSequence<KtElement>(this) {
            if (it is KtFile) return@generateSequence null
            it.parent as KtElement
        }.map { it.text.hashCode() }
        val number = trace.reduce { acc, it ->
            acc * 31 + it
        }.absoluteValue
        return "lambda\$$number"
    }

    private fun CallableMemberDescriptor.findSuperRoot(): CallableMemberDescriptor {
        return firstOverridden(true) { true }?.original ?: this
    }

    @Suppress("NAME_SHADOWING")
    private fun getContainerInternalName(descriptor: DeclarationDescriptorWithSource): String {
        if (descriptor is SamConstructorDescriptor) {
            return descriptor.baseDescriptorForSynthetic.classId!!.jvmName
        }

        val descriptor: DeclarationDescriptorWithSource = when (descriptor) {
            is SyntheticJavaPropertyDescriptor -> descriptor.getMethod
            else -> descriptor
        }

        val sequence = descriptor.parents.toList()
        val searchResult = sequence.withIndex().find { it.value is ClassOrPackageFragmentDescriptor }
            ?: throw NoSuchElementException("Unable to find container for descriptor: $descriptor")
        val (index, container) = searchResult
        val immediateChild = sequence.getOrNull(index - 1)

        if (container is ClassDescriptor) {
            if (container is ErrorClassDescriptor) {
                throw NoSuchElementException("Container of descriptor $descriptor is unresolved!")
            }
            if (container.classId == null) {
                return getContainerInternalName(container) + "\$${container.toSourceElement.getPsi()!!.text.hashCode()}"
            }

            val typeId = container.classId!!
            return typeId.jvmName
        }

        if (container is PackageFragmentDescriptor) {
            if (descriptor.source != SourceElement.NO_SOURCE) {
                return ((descriptor.source.containingFile as PsiSourceFile).psiFile as KtFile).javaFileFacadeFqName.joinSlash()
            }
            if (immediateChild?.toSourceElement != null && immediateChild.toSourceElement != SourceElement.NO_SOURCE) {
                return ((immediateChild.toSourceElement.containingFile as PsiSourceFile).psiFile as KtFile).javaFileFacadeFqName.joinSlash()
            }

            if (descriptor is DescriptorWithContainerSource) {
                return descriptor.getImplClassNameForDeserialized()?.internalName
                    ?: (descriptor.containingPackage()!!.joinSlash() + "/<built-in>")
            }

            if (immediateChild is DescriptorWithContainerSource) {
                return immediateChild.getImplClassNameForDeserialized()?.internalName
                    ?: (immediateChild.containingPackage()!!.joinSlash() + "/<built-in>")
            }
        }

        throw RuntimeException("Unable to parse owner!")
    }

    private fun FqName.joinSlash() = pathSegments().joinToString("/")

    private val KtFunction.descriptor: FunctionDescriptor
        get() = if (this is KtConstructor<*>) {
            bindingContext[BindingContext.CONSTRUCTOR, this]!!
        } else {
            bindingContext[BindingContext.FUNCTION, this]!!
        }
    

    private val KotlinType.classId: ClassId
        get() {
            if (this.containsError()) {
                throw RuntimeException("Unable to resolve type $this")
            }

            return when (val declaration = constructor.declarationDescriptor) {
                is TypeParameterDescriptor -> {
                    val bound = declaration.representativeUpperBound
                    bound.supertypes().first().classId
                }
                is ClassDescriptor -> {
                    declaration.classId!!
                }
                else -> {
                    throw RuntimeException("Unrecognized declaration descriptor $declaration for type $this")
                }
            }
        }

    private fun KtDeclarationWithBody.getNameMaybeLambda(descriptor: CallableDescriptor): String {
        val isAnonymous = descriptor.name.isAnonymous

        if (!isAnonymous) return descriptor.name.asString()

        return when (this) {
            is KtFunction -> {
                var lambdaName = parent.getUserData(lambdaName)
                if (lambdaName == null) {
                    lambdaName = (parent as KtLambdaExpression).generateJvmName()
                    parent.putUserData(this@KotlinWalker.lambdaName, lambdaName)
                }

                return lambdaName
            }

            is KtPropertyAccessor -> {
                namePlaceholder.text
            }

            else -> {
                throw RuntimeException("Unknown declaration type $text (${this::class.qualifiedName}")
            }
        }
    }

    private fun collectLocalVars(element: PsiElement, ignore: List<PsiElement>): List<PsiElement> {
        val toReturn = mutableListOf<PsiElement>()

        val localVarCollector = object : KtVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                if (element !is KtLambdaExpression) element.acceptChildren(this)
            }

            override fun visitDestructuringDeclarationEntry(multiDeclarationEntry: KtDestructuringDeclarationEntry) {
                toReturn += multiDeclarationEntry

                super.visitDestructuringDeclarationEntry(multiDeclarationEntry)
            }

            override fun visitProperty(property: KtProperty) {
                if (property.isLocal) {
                    toReturn += property
                }

                super.visitProperty(property)
            }

            override fun visitParameter(parameter: KtParameter) {
                if (!ignore.contains(parameter)) {
                    toReturn += parameter
                }

                super.visitParameter(parameter)
            }
        }
        element.accept(localVarCollector)

        return toReturn
    }

    private fun KtClassInitializer.localVariablesAndParams(): List<PsiElement> {
        if (getUserData(localVarParams) != null) return getUserData(localVarParams)!!

        val valueParameters = containingClass()?.primaryConstructor?.valueParameters ?: emptyList()
        val toReturn = mutableListOf<PsiElement>()
        toReturn.addAll(valueParameters)

        toReturn.addAll(collectLocalVars(this, valueParameters))

        putUserData(localVarParams, toReturn)
        return toReturn
    }

    private fun KtBlockExpression.localVariablesAndParams(descriptor: CallableDescriptor): List<PsiElement> {
        if (getUserData(localVarParams) != null) return getUserData(localVarParams)!!

        val valueParameters = if (parent is KtFunction) (parent as KtFunction).valueParameters else emptyList()
        val toReturn = mutableListOf<PsiElement>()
        toReturn.addAll(valueParameters)
        if (descriptor.valueParameters.isNotEmpty() && toReturn.isEmpty()) {
            toReturn += LeafPsiElement(KtNameReferenceExpressionElementType("it"), "it")
        }

        toReturn.addAll(collectLocalVars(parent, valueParameters))

        putUserData(localVarParams, toReturn)
        return toReturn
    }

    private fun <T> trimTrace(toRun: () -> T): T? {
        return try {
            toRun()
        } catch (throwable: Throwable) {
            throwable.message?.let { logger.errorLogger.println(it) }

            // trim stacktrace because, oh boy, it's hecking long!
            val index = throwable.stackTrace.indexOfFirst { it.methodName.contains("tryRun") } + 2
            val subList = throwable.stackTrace.asList().subList(0, index)
            throwable.stackTrace = subList.toTypedArray()
            throwable.printStackTrace(logger.errorLogger)
            null
        }
    }

    data class Unquoted(
        val startOffset: Int,
        val text: String,
        val textLength: Int
    )
    private fun unquote(element: PsiElement): Unquoted {
        val count = element.text.count { it == '`' }
        if (count > 2) throw IllegalStateException("Element with multiple quoting is unsupported!")
        if (element.text.contains(' ')) throw IllegalStateException("Element containing space is unsupported!")

        return if (count == 0) {
            Unquoted(element.startOffset, element.text, element.textLength)
        } else {
            val text = element.text.drop(1).dropLast(1)
            Unquoted(element.startOffset + 1, text, text.length)
        }
    }

    private var handled = false // It's development purposes. It's used to print out all unhandled cases
    // It's probably just going to clog up in production, so better to disable feature later

    data class VariableReferenceArguments(
        val methodContainer: String,
        val methodName: String,
        val methodDescriptor: String,
        val varIndex: Int
    ) {
        init {
            require(varIndex != -1)
        }
    }
}

// Notes on how original srg2source handles variables:
// srg2source treats variable declaration and reference as same thing
// and static and non-static fields as same thing

// There is a class called StructuralEntry. It adds indent, "# START", and "# END" to output range file.
// StructureEntry defines what can be StructureEntry, it's things like method, class, etc...
// I was thinking of somehow using that system to define property function of val/var
// But it seems impossible
// Well it's not an important element of range map, so it's fine to not define another entry

// In original srg2source, BodyDeclaration is for everything that can have body
// subclass, functions, etc
