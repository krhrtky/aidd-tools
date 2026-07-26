package dev.aidd.extractor.kotlin

import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtWhenExpression
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class KotlinExtractor {
    fun extract(repository: Path, allowBuildTool: Boolean): CodeFacts {
        val normalizedRepository = repository.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedRepository)) {
            "Repository does not exist or is not a directory: $normalizedRepository"
        }

        val realRepository = normalizedRepository.toRealPath()
        val sources = discoverSources(realRepository)
        val repositoryHash = repositoryHash(realRepository, sources)
        val disposable = Disposer.newDisposable("aidd-kotlin-extractor")
        return try {
            val environment = createEnvironment(disposable)
            val psiFactory = KtPsiFactory(environment.project, markGenerated = false)
            val extracted = sources.map { path ->
                val relativePath = normalizePath(realRepository.relativize(path))
                val bytes = Files.readAllBytes(path)
                val text = bytes.toString(Charsets.UTF_8)
                SourceFile(
                    relativePath = relativePath,
                    text = text,
                    sha256 = sha256(bytes),
                    ktFile = psiFactory.createFile(path.fileName.toString(), text),
                )
            }
            val facts = extracted
                .flatMap(::extractFile)
                .sortedWith(factComparator)
                .map(::withStableId)
            val diagnostics = extracted
                .flatMap { source -> diagnosticsFor(source, allowBuildTool) }
                .sortedWith(
                    compareBy(
                        { it.source?.path ?: "" },
                        { it.source?.startLine ?: 0 },
                        { it.code },
                        { it.message },
                    ),
                )

            CodeFacts(
                repositorySha256 = repositoryHash,
                facts = facts,
                diagnostics = diagnostics,
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun extractFile(source: SourceFile): List<CodeFact> = buildList {
        val declarations = PsiTreeUtil.findChildrenOfType(source.ktFile, KtNamedDeclaration::class.java)
        declarations
            .filter(::isPublicDeclaration)
            .forEach { declaration ->
                when (declaration) {
                    is KtClass -> add(typeFact(source, declaration))
                    is KtObjectDeclaration -> add(typeFact(source, declaration))
                    is KtNamedFunction -> add(callableFact(source, declaration))
                    is KtProperty -> add(propertyFact(source, declaration))
                    is KtTypeAlias -> add(typeAliasFact(source, declaration))
                }
            }

        PsiTreeUtil.findChildrenOfType(source.ktFile, KtCallExpression::class.java)
            .forEach { call ->
                val callee = call.calleeExpression?.text ?: return@forEach
                when {
                    callee in guardNames -> add(guardFact(source, call, callee))
                    isAssertion(call, source) -> add(assertionFact(source, call, callee))
                    else -> add(callFact(source, call, callee))
                }
            }

        PsiTreeUtil.findChildrenOfType(source.ktFile, KtThrowExpression::class.java)
            .forEach { add(simpleFact(source, it, "throw", "throw", details = mapOf("expression" to it.thrownExpression?.text))) }

        PsiTreeUtil.findChildrenOfType(source.ktFile, KtWhenExpression::class.java)
            .forEach { expression ->
                expression.entries.forEachIndexed { index, entry ->
                    add(
                        simpleFact(
                            source = source,
                            element = entry,
                            kind = "transition",
                            name = "when[$index]",
                            details = mapOf(
                                "subject" to expression.subjectExpression?.text,
                                "conditions" to entry.conditions.map { it.text },
                                "result" to entry.expression?.text,
                                "isElse" to entry.isElse,
                            ),
                        ),
                    )
                }
            }

        PsiTreeUtil.findChildrenOfType(source.ktFile, KtProperty::class.java)
            .filter { it.isLocal }
            .forEach { property ->
                add(
                    simpleFact(
                        source,
                        property,
                        "assignment",
                        property.name ?: "<anonymous>",
                        mapOf(
                            "operator" to "=",
                            "target" to property.name,
                            "value" to property.initializer?.text,
                            "mutable" to property.isVar,
                        ),
                    ),
                )
            }

        PsiTreeUtil.findChildrenOfType(source.ktFile, KtBinaryExpression::class.java)
            .filter { it.operationToken in assignmentTokens }
            .forEach { assignment ->
                add(
                    simpleFact(
                        source,
                        assignment,
                        "assignment",
                        assignment.left?.text ?: "<unknown>",
                        mapOf(
                            "operator" to assignment.operationReference.text,
                            "target" to assignment.left?.text,
                            "value" to assignment.right?.text,
                        ),
                    ),
                )
            }

        PsiTreeUtil.findChildrenOfType(source.ktFile, KtNamedFunction::class.java)
            .filter { isTestFunction(it, source) }
            .forEach { function ->
                add(
                    simpleFact(
                        source,
                        function,
                        "test",
                        function.name ?: "<anonymous>",
                        mapOf("annotations" to annotationNames(function)),
                    ),
                )
            }

        declarations.forEach { declaration ->
            val doc = declaration.docComment ?: return@forEach
            kdocLinks(doc).forEach { (tag, target) ->
                add(
                    simpleFact(
                        source,
                        doc,
                        "trace-link",
                        target,
                        mapOf(
                            "tag" to tag,
                            "target" to target,
                            "declaration" to qualifiedName(declaration),
                        ),
                    ),
                )
            }
        }
    }

    private fun typeFact(source: SourceFile, declaration: KtClassOrObject): CodeFact {
        val modality = when {
            declaration is KtClass && declaration.isEnum() -> "enum"
            declaration.hasModifier(KtTokens.SEALED_KEYWORD) -> "sealed"
            declaration.hasModifier(KtTokens.DATA_KEYWORD) -> "data"
            declaration is KtObjectDeclaration && declaration.isCompanion() -> "companion-object"
            declaration is KtObjectDeclaration -> "object"
            declaration is KtClass && declaration.isInterface() -> "interface"
            else -> "class"
        }
        val constructorParameters = (declaration as? KtClass)
            ?.primaryConstructorParameters
            .orEmpty()
            .map(::parameterDetails)
        return declarationFact(
            source,
            declaration,
            "type",
            mapOf(
                "declarationKind" to declaration::class.simpleName,
                "modality" to modality,
                "typeParameters" to declaration.typeParameters.mapNotNull { it.name },
                "supertypes" to declaration.superTypeListEntries.map { it.typeReference?.text ?: it.text },
                "constructorParameters" to constructorParameters,
                "enumEntries" to (declaration as? KtClass)
                    ?.declarations
                    .orEmpty()
                    .filterIsInstance<org.jetbrains.kotlin.psi.KtEnumEntry>()
                    .mapNotNull { it.name },
            ),
        )
    }

    private fun callableFact(source: SourceFile, declaration: KtNamedFunction): CodeFact =
        declarationFact(
            source,
            declaration,
            "callable",
            mapOf(
                "parameters" to declaration.valueParameters.map(::parameterDetails),
                "returnType" to declaration.typeReference?.text,
                "returnNullable" to declaration.typeReference?.text?.trim()?.endsWith("?"),
                "receiverType" to declaration.receiverTypeReference?.text,
                "suspend" to declaration.hasModifier(KtTokens.SUSPEND_KEYWORD),
                "operator" to declaration.hasModifier(KtTokens.OPERATOR_KEYWORD),
                "expressionBody" to (declaration.hasBody() && !declaration.hasBlockBody()),
            ),
        )

    private fun propertyFact(source: SourceFile, declaration: KtProperty): CodeFact =
        declarationFact(
            source,
            declaration,
            "property",
            mapOf(
                "type" to declaration.typeReference?.text,
                "nullable" to declaration.typeReference?.text?.trim()?.endsWith("?"),
                "mutable" to declaration.isVar,
                "delegated" to declaration.hasDelegate(),
                "initializer" to declaration.initializer?.text,
            ),
        )

    private fun typeAliasFact(source: SourceFile, declaration: KtTypeAlias): CodeFact =
        declarationFact(
            source,
            declaration,
            "type-alias",
            mapOf("expandedType" to declaration.getTypeReference()?.text),
        )

    private fun declarationFact(
        source: SourceFile,
        declaration: KtNamedDeclaration,
        kind: String,
        details: Map<String, Any?>,
    ): CodeFact = simpleFact(
        source = source,
        element = declaration,
        kind = kind,
        name = declaration.name ?: "<anonymous>",
        qualifiedName = qualifiedName(declaration),
        details = details + mapOf(
            "visibility" to visibility(declaration),
            "annotations" to annotationNames(declaration),
        ),
    )

    private fun guardFact(source: SourceFile, call: KtCallExpression, callee: String): CodeFact =
        simpleFact(
            source,
            call,
            "guard",
            callee,
            mapOf(
                "guardKind" to callee,
                "condition" to call.valueArguments.firstOrNull()?.getArgumentExpression()?.text,
                "message" to call.lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression?.text,
            ),
        )

    private fun assertionFact(source: SourceFile, call: KtCallExpression, callee: String): CodeFact =
        simpleFact(
            source,
            call,
            "assertion",
            callee,
            mapOf("arguments" to call.valueArguments.map { it.getArgumentExpression()?.text }),
        )

    private fun callFact(source: SourceFile, call: KtCallExpression, callee: String): CodeFact =
        simpleFact(
            source,
            call,
            "call",
            callee.substringAfterLast('.'),
            mapOf(
                "callee" to callee,
                "arguments" to call.valueArguments.map { it.getArgumentExpression()?.text },
                "typeArguments" to call.typeArgumentList?.arguments?.map { it.text }.orEmpty(),
            ),
        )

    private fun simpleFact(
        source: SourceFile,
        element: PsiElement,
        kind: String,
        name: String,
        details: Map<String, Any?>,
        qualifiedName: String = enclosingQualifiedName(element, source.ktFile, name),
    ): CodeFact = CodeFact(
        id = "",
        kind = kind,
        name = name,
        qualifiedName = qualifiedName,
        source = source.location(element),
        details = details.filterValues { it != null },
    )

    private fun diagnosticsFor(source: SourceFile, allowBuildTool: Boolean): List<Diagnostic> =
        buildList {
            PsiTreeUtil.findChildrenOfType(source.ktFile, PsiErrorElement::class.java)
                .forEach { error ->
                    add(
                        Diagnostic(
                            severity = "UNSUPPORTED",
                            code = "KOTLIN_SYNTAX_ERROR",
                            message = error.errorDescription,
                            source = source.location(error),
                        ),
                    )
                }
            PsiTreeUtil.findChildrenOfType(source.ktFile, KtNamedFunction::class.java)
                .filter(::isPublicDeclaration)
                .filter { it.hasBody() && !it.hasBlockBody() && it.typeReference == null }
                .forEach { declaration ->
                    add(semanticClasspathDiagnostic(source, declaration, allowBuildTool, "return type"))
                }
            PsiTreeUtil.findChildrenOfType(source.ktFile, KtProperty::class.java)
                .filter(::isPublicDeclaration)
                .filter { it.typeReference == null }
                .forEach { declaration ->
                    add(semanticClasspathDiagnostic(source, declaration, allowBuildTool, "property type"))
                }
        }

    private fun semanticClasspathDiagnostic(
        source: SourceFile,
        declaration: KtNamedDeclaration,
        allowBuildTool: Boolean,
        target: String,
    ): Diagnostic {
        val permission = if (allowBuildTool) {
            "Build-tool evaluation was permitted, but standalone classpath resolution is not implemented."
        } else {
            "Gradle scripts were not evaluated; pass --allow-build-tool only if build configuration evaluation is acceptable."
        }
        return Diagnostic(
            severity = "UNSUPPORTED",
            code = "SEMANTIC_CLASSPATH_REQUIRED",
            message = "The public $target of ${qualifiedName(declaration)} is inferred and requires semantic classpath analysis. $permission",
            source = source.location(declaration),
            details = mapOf(
                "allowBuildTool" to allowBuildTool,
                "declaration" to qualifiedName(declaration),
                "target" to target,
            ),
        )
    }

    private fun isPublicDeclaration(declaration: KtNamedDeclaration): Boolean {
        if (declaration is KtProperty && declaration.isLocal) return false
        if (declaration is KtNamedFunction && declaration.isLocal) return false
        if (declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) return false
        if (declaration.hasModifier(KtTokens.PROTECTED_KEYWORD)) return false
        if (declaration.hasModifier(KtTokens.INTERNAL_KEYWORD)) return false
        val parentDeclaration = PsiTreeUtil.getParentOfType(
            declaration,
            KtNamedDeclaration::class.java,
            true,
        )
        return parentDeclaration == null || isPublicDeclaration(parentDeclaration)
    }

    private fun isTestFunction(function: KtNamedFunction, source: SourceFile): Boolean =
        annotationNames(function).any { it.substringAfterLast('.') in testAnnotationNames } ||
            source.relativePath.contains("/test/", ignoreCase = true)

    private fun isAssertion(call: KtCallExpression, source: SourceFile): Boolean {
        val callee = call.calleeExpression?.text?.substringAfterLast('.') ?: return false
        return callee.startsWith("assert") &&
            (source.relativePath.contains("/test/", ignoreCase = true) ||
                PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java)?.let {
                    annotationNames(it).any { annotation -> annotation.substringAfterLast('.') in testAnnotationNames }
                } == true)
    }

    private fun annotationNames(annotated: KtAnnotated): List<String> =
        annotated.annotationEntries.map { it.shortName?.asString() ?: it.typeReference?.text ?: it.text }

    private fun parameterDetails(parameter: org.jetbrains.kotlin.psi.KtParameter): Map<String, Any?> =
        mapOf(
            "name" to parameter.name,
            "type" to parameter.typeReference?.text,
            "nullable" to parameter.typeReference?.text?.trim()?.endsWith("?"),
            "defaultValue" to parameter.defaultValue?.text,
            "vararg" to parameter.hasModifier(KtTokens.VARARG_KEYWORD),
            "property" to when {
                parameter.hasValOrVar() && parameter.isMutable -> "var"
                parameter.hasValOrVar() -> "val"
                else -> null
            },
        ).filterValues { it != null }

    private fun kdocLinks(doc: KDoc): List<Pair<String, String>> =
        kdocTagRegex.findAll(doc.text)
            .map { match -> "aidd.${match.groupValues[1]}" to match.groupValues[2] }
            .toList()

    private fun qualifiedName(declaration: KtNamedDeclaration): String {
        val names = generateSequence(declaration as PsiElement?) { element ->
            PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, true)
        }.mapNotNull { (it as? KtNamedDeclaration)?.name }
            .toList()
            .asReversed()
        val packageName = declaration.containingKtFile.packageFqName.asString()
        return (listOf(packageName).filter { it.isNotBlank() } + names).joinToString(".")
    }

    private fun enclosingQualifiedName(element: PsiElement, file: KtFile, name: String): String {
        val declaration = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
        if (declaration != null) return "${qualifiedName(declaration)}#$name"
        val packageName = file.packageFqName.asString()
        return listOf(packageName, name).filter { it.isNotBlank() }.joinToString(".")
    }

    private fun visibility(declaration: KtDeclaration): String = when {
        declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
        declaration.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> "public"
    }

    private fun withStableId(fact: CodeFact): CodeFact {
        val key = listOf(
            fact.kind,
            fact.qualifiedName,
            fact.source.path,
            fact.source.startLine.toString(),
            fact.source.startColumn.toString(),
        ).joinToString("\u0000")
        return fact.copy(id = "urn:aidd:kotlin:${sha256(key).take(32)}")
    }

    private fun discoverSources(repository: Path): List<Path> =
        Files.walk(repository).use { paths ->
            val kotlinPaths = paths
                .filter { it.fileName.toString().endsWith(".kt") }
                .toList()
            kotlinPaths.forEach { path ->
                require(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(repository)) {
                    "Kotlin source uses a symlink or escapes repository: $path"
                }
            }
            kotlinPaths
                .asSequence()
                .filter { it.isRegularFile() }
                .filter { path ->
                    val relative = normalizePath(repository.relativize(path))
                    ignoredPathSegments.none { ignored -> relative.split('/').contains(ignored) }
                }
                .sortedWith(compareBy { normalizePath(repository.relativize(it)) })
                .toList()
        }

    private fun repositoryHash(repository: Path, sources: List<Path>): String {
        val digestInput = buildString {
            sources.forEach { path ->
                val relative = normalizePath(repository.relativize(path))
                append(relative)
                append('\u0000')
                append(sha256(Files.readAllBytes(path)))
                append('\n')
            }
        }
        return sha256(digestInput)
    }

    @OptIn(org.jetbrains.kotlin.K1Deprecation::class)
    private fun createEnvironment(disposable: Disposable): KotlinCoreEnvironment {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "aidd-kotlin-extractor")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        return KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    private data class SourceFile(
        val relativePath: String,
        val text: String,
        val sha256: String,
        val ktFile: KtFile,
    ) {
        fun location(element: PsiElement): SourceLocation {
            val range = element.textRange
            val (startLine, startColumn) = lineAndColumn(range.startOffset)
            val (endLine, endColumn) = lineAndColumn(range.endOffset)
            return SourceLocation(
                path = relativePath,
                startLine = startLine,
                startColumn = startColumn,
                endLine = endLine,
                endColumn = endColumn,
                sha256 = sha256,
            )
        }

        private fun lineAndColumn(offset: Int): Pair<Int, Int> {
            val safeOffset = offset.coerceIn(0, text.length)
            var line = 1
            var column = 1
            for (index in 0 until safeOffset) {
                if (text[index] == '\n') {
                    line += 1
                    column = 1
                } else {
                    column += 1
                }
            }
            return line to column
        }
    }

    companion object {
        private val guardNames = setOf("require", "requireNotNull", "check", "checkNotNull")
        private val testAnnotationNames = setOf("Test", "ParameterizedTest", "TestFactory", "TestTemplate")
        private val ignoredPathSegments = setOf(".git", ".gradle", "build", "out", "node_modules")
        private val assignmentTokens = setOf(
            KtTokens.EQ,
            KtTokens.PLUSEQ,
            KtTokens.MINUSEQ,
            KtTokens.MULTEQ,
            KtTokens.DIVEQ,
            KtTokens.PERCEQ,
        )
        private val kdocTagRegex = Regex("""@aidd\.(requirement|verifies)\s+([A-Za-z0-9_.:/-]+)""")
        private val factComparator = compareBy<CodeFact>(
            { it.source.path },
            { it.source.startLine },
            { it.source.startColumn },
            { it.kind },
            { it.qualifiedName },
        )

        private fun normalizePath(path: Path): String = path.toString().replace('\\', '/')
    }
}
