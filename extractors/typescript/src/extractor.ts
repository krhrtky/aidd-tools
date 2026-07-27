import { createHash } from "node:crypto";
import { lstat, readFile, realpath } from "node:fs/promises";
import path from "node:path";

import ts from "typescript";
import { parse as parseYaml } from "yaml";

import {
  normalizeObservedContract,
  UnsupportedObservedContract,
} from "./contract-normalizer.js";
import type {
  CodeFact,
  ExtractRepositoryOptions,
  ExtractionDiagnostic,
  ExtractionResult,
  SourceReference,
} from "./types.js";

const TEST_FILE = /(?:^|\/)[^/]*(?:\.test|\.spec)\.[cm]?[jt]sx?$/u;
const ASSERTION_MATCHERS = new Set([
  "toBe",
  "toEqual",
  "toStrictEqual",
  "toMatch",
  "toContain",
  "toThrow",
  "toBeTruthy",
  "toBeFalsy",
  "toBeNull",
  "toBeUndefined",
  "toBeDefined",
  "toHaveLength",
  "toHaveProperty",
  "equal",
  "deepEqual",
  "deepStrictEqual",
  "strictEqual",
  "ok",
  "throws",
  "rejects",
]);
const ASSIGNMENT_OPERATORS = new Set<ts.SyntaxKind>([
  ts.SyntaxKind.EqualsToken,
  ts.SyntaxKind.PlusEqualsToken,
  ts.SyntaxKind.MinusEqualsToken,
  ts.SyntaxKind.AsteriskEqualsToken,
  ts.SyntaxKind.SlashEqualsToken,
  ts.SyntaxKind.PercentEqualsToken,
  ts.SyntaxKind.AmpersandEqualsToken,
  ts.SyntaxKind.BarEqualsToken,
  ts.SyntaxKind.CaretEqualsToken,
  ts.SyntaxKind.LessThanLessThanEqualsToken,
  ts.SyntaxKind.GreaterThanGreaterThanEqualsToken,
  ts.SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken,
  ts.SyntaxKind.AsteriskAsteriskEqualsToken,
  ts.SyntaxKind.BarBarEqualsToken,
  ts.SyntaxKind.AmpersandAmpersandEqualsToken,
  ts.SyntaxKind.QuestionQuestionEqualsToken,
]);

interface SourceContext {
  repo: string;
  file: ts.SourceFile;
  relativePath: string;
  contents: string;
  sha256: string;
}

interface CallableContext {
  name: string;
  qualifiedName: string;
  body: ts.Node;
  declaration:
    | ts.FunctionDeclaration
    | ts.MethodDeclaration
    | ts.ArrowFunction
    | ts.FunctionExpression;
  contractIds: string[];
}

function sha256(value: string | Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}

function normalizePath(value: string): string {
  return value.split(path.sep).join("/");
}

function canonicalText(node: ts.Node, sourceFile: ts.SourceFile): string {
  return node.getText(sourceFile).replace(/\s+/gu, " ").trim();
}

function sourceReference(
  context: SourceContext,
  node: ts.Node,
): SourceReference {
  const start = context.file.getLineAndCharacterOfPosition(
    node.getStart(context.file),
  );
  const end = context.file.getLineAndCharacterOfPosition(node.getEnd());
  return {
    path: context.relativePath,
    startLine: start.line + 1,
    startColumn: start.character + 1,
    endLine: end.line + 1,
    endColumn: end.character + 1,
    sha256: context.sha256,
  };
}

function wholeFileReference(
  repo: string,
  absolutePath: string,
  contents: string,
): SourceReference {
  const lines = contents.split("\n");
  return {
    path: normalizePath(path.relative(repo, absolutePath)),
    startLine: 1,
    startColumn: 1,
    endLine: lines.length,
    endColumn: (lines.at(-1)?.length ?? 0) + 1,
    sha256: sha256(contents),
  };
}

function factId(
  kind: string,
  qualifiedName: string,
  source: SourceReference,
): string {
  return `ts:${kind}:${sha256(
    `${qualifiedName}\0${source.path}\0${source.startLine}:${source.startColumn}`,
  ).slice(0, 24)}`;
}

function createFact(
  kind: string,
  name: string,
  qualifiedName: string,
  source: SourceReference,
  details: Record<string, unknown>,
): CodeFact {
  return {
    id: factId(kind, qualifiedName, source),
    kind,
    name,
    qualifiedName,
    status: "accepted",
    basis: "observed",
    source,
    details,
  };
}

function hasModifier(node: ts.Node, kind: ts.SyntaxKind): boolean {
  return ts.getModifiers(node as ts.HasModifiers)?.some(
    (modifier) => modifier.kind === kind,
  ) ?? false;
}

function isExported(node: ts.Node): boolean {
  return (
    hasModifier(node, ts.SyntaxKind.ExportKeyword) ||
    hasModifier(node, ts.SyntaxKind.DefaultKeyword)
  );
}

function isPublicMember(node: ts.Node): boolean {
  return !hasModifier(node, ts.SyntaxKind.PrivateKeyword) &&
    !hasModifier(node, ts.SyntaxKind.ProtectedKeyword);
}

function moduleQualifier(context: SourceContext, name: string): string {
  return `${context.relativePath}#${name}`;
}

function nullableType(type: ts.Type): boolean {
  if ((type.flags & (ts.TypeFlags.Null | ts.TypeFlags.Undefined)) !== 0) {
    return true;
  }
  return type.isUnion() && type.types.some(nullableType);
}

function typeText(
  checker: ts.TypeChecker,
  declaration: ts.Node,
  explicitType?: ts.TypeNode,
): string {
  if (explicitType) {
    return canonicalText(explicitType, declaration.getSourceFile());
  }
  return checker.typeToString(
    checker.getTypeAtLocation(declaration),
    declaration,
    ts.TypeFormatFlags.NoTruncation,
  );
}

function jsDocCommentText(comment: ts.JSDocTag["comment"]): string {
  if (typeof comment === "string") {
    return comment.trim();
  }
  return comment?.map((part) => part.text).join("").trim() ?? "";
}

function aiddLinks(node: ts.Node): {
  requirementIds: string[];
  verifiesIds: string[];
  contractIds: string[];
} {
  const requirements = new Set<string>();
  const verifies = new Set<string>();
  const contracts = new Set<string>();
  for (const tag of ts.getJSDocTags(node)) {
    const rawValue = jsDocCommentText(tag.comment);
    const dottedTag = tag.tagName.text === "aidd"
      ? rawValue.match(/^\.(requirement|verifies|contract)\s+(.+)$/u)
      : undefined;
    const tagName = dottedTag ? `aidd.${dottedTag[1]}` : tag.tagName.text;
    const value = dottedTag?.[2]?.trim() ?? rawValue;
    if (tagName === "aidd.requirement" && value) {
      requirements.add(value);
    }
    if (tagName === "aidd.verifies" && value) {
      verifies.add(value);
    }
    if (tagName === "aidd.contract" && value) {
      contracts.add(value);
    }
  }
  return {
    requirementIds: [...requirements].sort(),
    verifiesIds: [...verifies].sort(),
    contractIds: [...contracts].sort(),
  };
}

function declarationTypeDetails(
  checker: ts.TypeChecker,
  declaration: ts.InterfaceDeclaration | ts.TypeAliasDeclaration,
): Record<string, unknown> {
  const symbol = checker.getSymbolAtLocation(declaration.name);
  const declaredType = symbol
    ? checker.getDeclaredTypeOfSymbol(symbol)
    : checker.getTypeAtLocation(declaration);
  const properties = checker
    .getPropertiesOfType(declaredType)
    .map((property) => {
      const propertyDeclaration = property.valueDeclaration ??
        property.declarations?.[0];
      const propertyType = propertyDeclaration
        ? checker.getTypeOfSymbolAtLocation(property, propertyDeclaration)
        : checker.getAnyType();
      return {
        name: property.getName(),
        optional: (property.flags & ts.SymbolFlags.Optional) !== 0,
        nullable: nullableType(propertyType),
        type: propertyDeclaration
          ? checker.typeToString(
              propertyType,
              propertyDeclaration,
              ts.TypeFormatFlags.NoTruncation,
            )
          : "unknown",
      };
    })
    .sort((left, right) => left.name.localeCompare(right.name));
  return {
    exported: true,
    properties,
    ...aiddLinks(declaration),
  };
}

function discriminatedUnionDetails(
  checker: ts.TypeChecker,
  declaration: ts.TypeAliasDeclaration,
): Record<string, unknown> | undefined {
  if (!ts.isUnionTypeNode(declaration.type)) {
    return undefined;
  }
  const variants = declaration.type.types.map((variant) => {
    if (!ts.isTypeLiteralNode(variant)) {
      return undefined;
    }
    const literalProperties = variant.members
      .filter(ts.isPropertySignature)
      .flatMap((property) => {
        if (
          !property.name ||
          !property.type ||
          !ts.isLiteralTypeNode(property.type) ||
          !ts.isStringLiteral(property.type.literal)
        ) {
          return [];
        }
        return [{
          property: canonicalText(property.name, declaration.getSourceFile()),
          value: property.type.literal.text,
        }];
      });
    return { node: variant, literalProperties };
  });
  if (variants.some((variant) => !variant)) {
    return undefined;
  }
  const completeVariants = variants.filter(
    (variant): variant is NonNullable<typeof variant> => Boolean(variant),
  );
  const discriminator = completeVariants[0]?.literalProperties.find(
    (candidate) =>
      completeVariants.every((variant) =>
        variant.literalProperties.some(
          (property) => property.property === candidate.property,
        )),
  )?.property;
  if (!discriminator) {
    return undefined;
  }
  return {
    exported: true,
    discriminator,
    variants: completeVariants.map((variant) => ({
      discriminatorValue: variant.literalProperties.find(
        (property) => property.property === discriminator,
      )?.value,
      type: checker.typeToString(
        checker.getTypeAtLocation(variant.node),
        variant.node,
        ts.TypeFormatFlags.NoTruncation,
      ),
    })),
    ...aiddLinks(declaration),
  };
}

function enumDetails(
  checker: ts.TypeChecker,
  declaration: ts.EnumDeclaration,
): Record<string, unknown> {
  return {
    exported: true,
    members: declaration.members.map((member) => ({
      name: canonicalText(member.name, declaration.getSourceFile()),
      value: checker.getConstantValue(member) ??
        (member.initializer
          ? canonicalText(member.initializer, declaration.getSourceFile())
          : undefined),
    })),
    ...aiddLinks(declaration),
  };
}

function signatureDetails(
  checker: ts.TypeChecker,
  declaration:
    | ts.FunctionDeclaration
    | ts.MethodDeclaration
    | ts.ArrowFunction
    | ts.FunctionExpression,
): Record<string, unknown> {
  const signature = checker.getSignatureFromDeclaration(declaration);
  const returnType = declaration.type
    ? typeText(checker, declaration, declaration.type)
    : signature
      ? checker.typeToString(
          checker.getReturnTypeOfSignature(signature),
          declaration,
          ts.TypeFormatFlags.NoTruncation,
        )
      : "unknown";
  const resolvedReturnType = signature
    ? checker.getReturnTypeOfSignature(signature)
    : checker.getTypeAtLocation(declaration);
  return {
    exported: ts.isFunctionDeclaration(declaration)
      ? isExported(declaration)
      : undefined,
    public: ts.isMethodDeclaration(declaration)
      ? isPublicMember(declaration)
      : undefined,
    async: hasModifier(declaration, ts.SyntaxKind.AsyncKeyword),
    parameters: declaration.parameters.map((parameter) => {
      const parameterType = checker.getTypeAtLocation(parameter);
      return {
        name: canonicalText(parameter.name, declaration.getSourceFile()),
        nullable: nullableType(parameterType),
        optional: Boolean(parameter.questionToken || parameter.initializer),
        type: typeText(checker, parameter, parameter.type),
      };
    }),
    returnType,
    nullableReturn: nullableType(resolvedReturnType),
    ...aiddLinks(declaration),
  };
}

function classDetails(
  declaration: ts.ClassDeclaration,
): Record<string, unknown> {
  const sourceFile = declaration.getSourceFile();
  return {
    exported: true,
    abstract: hasModifier(declaration, ts.SyntaxKind.AbstractKeyword),
    publicMembers: declaration.members
      .filter(isPublicMember)
      .flatMap((member) =>
        member.name
          ? [canonicalText(member.name, sourceFile)]
          : ts.isConstructorDeclaration(member)
            ? ["constructor"]
            : [],
      ),
    ...aiddLinks(declaration),
  };
}

function isAnyLike(type: ts.Type): boolean {
  return (type.flags & ts.TypeFlags.Any) !== 0 ||
    (type.isUnion() && type.types.some(isAnyLike));
}

function isAssertionCall(node: ts.CallExpression): boolean {
  if (ts.isIdentifier(node.expression)) {
    return ASSERTION_MATCHERS.has(node.expression.text);
  }
  return ts.isPropertyAccessExpression(node.expression) &&
    ASSERTION_MATCHERS.has(node.expression.name.text);
}

function assertionName(node: ts.CallExpression): string {
  return ts.isPropertyAccessExpression(node.expression)
    ? node.expression.name.text
    : node.expression.getText(node.getSourceFile());
}

function diagnostic(
  code: string,
  message: string,
  context: SourceContext,
  node: ts.Node,
): ExtractionDiagnostic {
  return {
    code,
    severity: "warning",
    message,
    source: sourceReference(context, node),
  };
}

function walkBehavior(
  checker: ts.TypeChecker,
  context: SourceContext,
  callable: CallableContext,
  facts: CodeFact[],
  diagnostics: ExtractionDiagnostic[],
): void {
  const visit = (node: ts.Node): void => {
    if (ts.isIfStatement(node)) {
      const name = canonicalText(node.expression, context.file);
      const source = sourceReference(context, node.expression);
      facts.push(
        createFact(
          "guard",
          name,
          `${callable.qualifiedName}:guard:${source.startLine}:${source.startColumn}`,
          source,
          { operation: callable.qualifiedName, expression: name },
        ),
      );
    }

    if (ts.isThrowStatement(node) && node.expression) {
      const name = canonicalText(node.expression, context.file);
      const source = sourceReference(context, node);
      const thrownType = checker.typeToString(
        checker.getTypeAtLocation(node.expression),
        node.expression,
        ts.TypeFormatFlags.NoTruncation,
      );
      facts.push(
        createFact(
          "throw",
          name,
          `${callable.qualifiedName}:throw:${source.startLine}:${source.startColumn}`,
          source,
          { operation: callable.qualifiedName, expression: name, thrownType },
        ),
      );
    }

    if (
      ts.isBinaryExpression(node) &&
      ASSIGNMENT_OPERATORS.has(node.operatorToken.kind)
    ) {
      const name = canonicalText(node.left, context.file);
      const source = sourceReference(context, node);
      facts.push(
        createFact(
          "assignment",
          name,
          `${callable.qualifiedName}:assignment:${source.startLine}:${source.startColumn}`,
          source,
          {
            operation: callable.qualifiedName,
            operator: node.operatorToken.getText(context.file),
            target: name,
            value: canonicalText(node.right, context.file),
          },
        ),
      );
    }

    if (ts.isCallExpression(node)) {
      if (isAnyLike(checker.getTypeAtLocation(node.expression))) {
        diagnostics.push(
          diagnostic(
            "DYNAMIC_CALL",
            `Dynamic call was not accepted as a semantic fact: ${canonicalText(node, context.file)}`,
            context,
            node,
          ),
        );
      } else {
        const signature = checker.getResolvedSignature(node);
        if (!signature?.declaration) {
          diagnostics.push(
            diagnostic(
              "UNRESOLVED_CALL",
              `Unresolved call was not accepted as a semantic fact: ${canonicalText(node, context.file)}`,
              context,
              node,
            ),
          );
        } else {
          const symbol = checker.getSymbolAtLocation(
            ts.isPropertyAccessExpression(node.expression)
              ? node.expression.name
              : node.expression,
          );
          const name = ts.isPropertyAccessExpression(node.expression)
            ? node.expression.name.text
            : canonicalText(node.expression, context.file);
          const source = sourceReference(context, node);
          facts.push(
            createFact(
              "call",
              name,
              `${callable.qualifiedName}:call:${source.startLine}:${source.startColumn}`,
              source,
              {
                operation: callable.qualifiedName,
                callee: name,
                resolvedQualifiedName: symbol
                  ? checker.getFullyQualifiedName(symbol)
                  : name,
              },
            ),
          );
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(callable.body);
}

function extractTestAssertions(
  context: SourceContext,
  facts: CodeFact[],
): void {
  if (!TEST_FILE.test(context.relativePath)) {
    return;
  }
  const visit = (node: ts.Node): void => {
    if (ts.isCallExpression(node) && isAssertionCall(node)) {
      const name = assertionName(node);
      const source = sourceReference(context, node);
      facts.push(
        createFact(
          "testAssertion",
          name,
          `${context.relativePath}#assertion:${source.startLine}:${source.startColumn}`,
          source,
          {
            framework: ts.isPropertyAccessExpression(node.expression)
              ? "expect"
              : "node-assert",
            expression: canonicalText(node, context.file),
            ...aiddLinks(node),
          },
        ),
      );
    }
    ts.forEachChild(node, visit);
  };
  visit(context.file);
}

function extractSourceFile(
  checker: ts.TypeChecker,
  context: SourceContext,
): {
  facts: CodeFact[];
  diagnostics: ExtractionDiagnostic[];
} {
  const facts: CodeFact[] = [];
  const diagnostics: ExtractionDiagnostic[] = [];
  const callables: CallableContext[] = [];

  for (const statement of context.file.statements) {
    if (!isExported(statement)) {
      continue;
    }

    if (ts.isInterfaceDeclaration(statement)) {
      const source = sourceReference(context, statement);
      facts.push(
        createFact(
          "interface",
          statement.name.text,
          moduleQualifier(context, statement.name.text),
          source,
          declarationTypeDetails(checker, statement),
        ),
      );
      continue;
    }

    if (ts.isTypeAliasDeclaration(statement)) {
      const source = sourceReference(context, statement);
      const unionDetails = discriminatedUnionDetails(checker, statement);
      facts.push(
        createFact(
          unionDetails ? "discriminatedUnion" : "typeAlias",
          statement.name.text,
          moduleQualifier(context, statement.name.text),
          source,
          unionDetails ?? {
            exported: true,
            type: canonicalText(statement.type, context.file),
            ...aiddLinks(statement),
          },
        ),
      );
      continue;
    }

    if (ts.isEnumDeclaration(statement)) {
      const source = sourceReference(context, statement);
      facts.push(
        createFact(
          "enum",
          statement.name.text,
          moduleQualifier(context, statement.name.text),
          source,
          enumDetails(checker, statement),
        ),
      );
      continue;
    }

    if (ts.isFunctionDeclaration(statement) && statement.name) {
      const qualifiedName = moduleQualifier(context, statement.name.text);
      facts.push(
        createFact(
          "function",
          statement.name.text,
          qualifiedName,
          sourceReference(context, statement),
          signatureDetails(checker, statement),
        ),
      );
      if (statement.body) {
        const links = aiddLinks(statement);
        callables.push({
          name: statement.name.text,
          qualifiedName,
          body: statement.body,
          declaration: statement,
          contractIds: links.contractIds,
        });
      }
      continue;
    }

    if (ts.isClassDeclaration(statement) && statement.name) {
      const classQualifiedName = moduleQualifier(context, statement.name.text);
      facts.push(
        createFact(
          "class",
          statement.name.text,
          classQualifiedName,
          sourceReference(context, statement),
          classDetails(statement),
        ),
      );
      for (const member of statement.members) {
        if (
          ts.isMethodDeclaration(member) &&
          member.name &&
          isPublicMember(member)
        ) {
          const memberName = canonicalText(member.name, context.file);
          const qualifiedName = `${classQualifiedName}.${memberName}`;
          facts.push(
            createFact(
              "method",
              memberName,
              qualifiedName,
              sourceReference(context, member),
              signatureDetails(checker, member),
            ),
          );
          if (member.body) {
            const links = aiddLinks(member);
            callables.push({
              name: memberName,
              qualifiedName,
              body: member.body,
              declaration: member,
              contractIds: links.contractIds,
            });
          }
        }
      }
      continue;
    }

    if (ts.isVariableStatement(statement)) {
      for (const declaration of statement.declarationList.declarations) {
        if (!ts.isIdentifier(declaration.name)) {
          continue;
        }
        const name = declaration.name.text;
        const callableInitializer = declaration.initializer &&
            (ts.isArrowFunction(declaration.initializer) ||
              ts.isFunctionExpression(declaration.initializer))
          ? declaration.initializer
          : undefined;
        if (callableInitializer) {
          const qualifiedName = moduleQualifier(context, name);
          facts.push(
            createFact(
              "function",
              name,
              qualifiedName,
              sourceReference(context, declaration),
              {
                ...signatureDetails(checker, callableInitializer),
                exported: true,
                ...aiddLinks(statement),
              },
            ),
          );
          callables.push({
            name,
            qualifiedName,
            body: callableInitializer.body,
            declaration: callableInitializer,
            contractIds: aiddLinks(statement).contractIds,
          });
          continue;
        }
        const valueType = checker.getTypeAtLocation(declaration);
        facts.push(
          createFact(
            "variable",
            name,
            moduleQualifier(context, name),
            sourceReference(context, declaration),
            {
              exported: true,
              readonly:
                (statement.declarationList.flags & ts.NodeFlags.Const) !== 0,
              nullable: nullableType(valueType),
              type: typeText(checker, declaration, declaration.type),
              ...aiddLinks(statement),
            },
          ),
        );
      }
    }
  }

  for (const callable of callables) {
    walkBehavior(checker, context, callable, facts, diagnostics);
    if (callable.contractIds.length > 0) {
      try {
        const observed = normalizeObservedContract(
          checker,
          callable.declaration,
          callable.qualifiedName,
          callable.contractIds,
        );
        facts.push(
          createFact(
            "observedContract",
            callable.name,
            `${callable.qualifiedName}:observed-contract`,
            sourceReference(context, callable.declaration),
            observed as unknown as Record<string, unknown>,
          ),
        );
      } catch (error) {
        if (!(error instanceof UnsupportedObservedContract)) throw error;
        diagnostics.push({
          code: "UNSUPPORTED_OBSERVED_CONTRACT",
          severity: "unsupported",
          message: `${callable.qualifiedName}: ${error.message}`,
          source: sourceReference(context, callable.declaration),
        });
      }
    }
  }
  extractTestAssertions(context, facts);
  return { facts, diagnostics };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function contractFacts(
  repo: string,
  absolutePath: string,
  contents: string,
): {
  facts: CodeFact[];
  diagnostics: ExtractionDiagnostic[];
} {
  const source = wholeFileReference(repo, absolutePath, contents);
  let document: unknown;
  try {
    document = absolutePath.endsWith(".json")
      ? JSON.parse(contents)
      : parseYaml(contents);
  } catch (error) {
    return {
      facts: [],
      diagnostics: [{
        code: "INVALID_CONTRACT",
        severity: "error",
        message: error instanceof Error ? error.message : String(error),
        source,
      }],
    };
  }
  if (!isRecord(document)) {
    return {
      facts: [],
      diagnostics: [{
        code: "UNSUPPORTED_CONTRACT",
        severity: "warning",
        message: "Contract root must be an object",
        source,
      }],
    };
  }

  const facts: CodeFact[] = [];
  if (typeof document.openapi === "string" && isRecord(document.paths)) {
    for (const route of Object.keys(document.paths).sort()) {
      const pathItem = document.paths[route];
      if (!isRecord(pathItem)) {
        continue;
      }
      for (
        const method of ["delete", "get", "head", "options", "patch", "post", "put", "trace"]
      ) {
        const operation = pathItem[method];
        if (!isRecord(operation)) {
          continue;
        }
        const operationId = typeof operation.operationId === "string"
          ? operation.operationId
          : `${method.toUpperCase()} ${route}`;
        const qualifiedName = `${source.path}#${method.toUpperCase()} ${route}`;
        facts.push(
          createFact(
            "openApiOperation",
            operationId,
            qualifiedName,
            source,
            {
              contractType: "openapi",
              openapiVersion: document.openapi,
              method: method.toUpperCase(),
              path: route,
              operationId,
              responseStatuses: isRecord(operation.responses)
                ? Object.keys(operation.responses).sort()
                : [],
            },
          ),
        );
      }
    }
    return { facts, diagnostics: [] };
  }

  if (
    typeof document.$schema === "string" ||
    typeof document.type === "string" ||
    isRecord(document.properties)
  ) {
    const name = typeof document.$id === "string"
      ? document.$id
      : path.basename(absolutePath).replace(/\.schema\.json$|\.json$/u, "");
    facts.push(
      createFact(
        "jsonSchema",
        name,
        `${source.path}#${name}`,
        source,
        {
          contractType: "json-schema",
          dialect: typeof document.$schema === "string"
            ? document.$schema
            : undefined,
          type: typeof document.type === "string" ? document.type : undefined,
          required: Array.isArray(document.required)
            ? document.required.filter(
                (item): item is string => typeof item === "string",
              ).sort()
            : [],
          properties: isRecord(document.properties)
            ? Object.keys(document.properties).sort()
            : [],
        },
      ),
    );
    return { facts, diagnostics: [] };
  }

  return {
    facts: [],
    diagnostics: [{
      code: "UNSUPPORTED_CONTRACT",
      severity: "warning",
      message: "File is neither an OpenAPI document nor a JSON Schema",
      source,
    }],
  };
}

function compareFacts(left: CodeFact, right: CodeFact): number {
  return left.source.path.localeCompare(right.source.path) ||
    left.source.startLine - right.source.startLine ||
    left.source.startColumn - right.source.startColumn ||
    left.kind.localeCompare(right.kind) ||
    left.qualifiedName.localeCompare(right.qualifiedName);
}

function compareDiagnostics(
  left: ExtractionDiagnostic,
  right: ExtractionDiagnostic,
): number {
  return (left.source?.path ?? "").localeCompare(right.source?.path ?? "") ||
    (left.source?.startLine ?? 0) - (right.source?.startLine ?? 0) ||
    (left.source?.startColumn ?? 0) - (right.source?.startColumn ?? 0) ||
    left.code.localeCompare(right.code) ||
    left.message.localeCompare(right.message);
}

export async function extractRepository(
  options: ExtractRepositoryOptions,
): Promise<ExtractionResult> {
  const repo = await realpath(path.resolve(options.repo));
  const configPath = ts.findConfigFile(repo, ts.sys.fileExists, "tsconfig.json");
  if (!configPath) {
    throw new Error(`tsconfig.json not found under ${repo}`);
  }
  const configStat = await lstat(configPath);
  const realConfigPath = await realpath(configPath);
  if (configStat.isSymbolicLink() || !isWithin(repo, realConfigPath)) {
    throw new Error(`tsconfig.json uses a symlink or escapes repository: ${configPath}`);
  }
  const config = ts.readConfigFile(configPath, ts.sys.readFile);
  if (config.error) {
    throw new Error(
      ts.flattenDiagnosticMessageText(config.error.messageText, "\n"),
    );
  }
  const parsedConfig = ts.parseJsonConfigFileContent(
    config.config,
    ts.sys,
    path.dirname(configPath),
    { noEmit: true },
    configPath,
  );
  const discoveredTestFiles = ts.sys.readDirectory(
    repo,
    [".ts", ".tsx", ".mts", ".cts"],
    ["**/node_modules/**", "**/dist/**"],
    [
      "**/*.test.ts",
      "**/*.test.tsx",
      "**/*.test.mts",
      "**/*.test.cts",
      "**/*.spec.ts",
      "**/*.spec.tsx",
      "**/*.spec.mts",
      "**/*.spec.cts",
    ],
  );
  const rootNames = [...new Set([
    ...parsedConfig.fileNames.map((fileName) => path.resolve(fileName)),
    ...discoveredTestFiles.map((fileName) => path.resolve(fileName)),
  ])].sort((left, right) => left.localeCompare(right));
  const program = ts.createProgram({
    rootNames,
    options: parsedConfig.options,
    projectReferences: parsedConfig.projectReferences,
  });
  const checker = program.getTypeChecker();
  const facts: CodeFact[] = [];
  const diagnostics: ExtractionDiagnostic[] = [];
  const repositoryEntries: Array<{ path: string; sha256: string }> = [];

  const configContents = await readFile(configPath, "utf8");
  repositoryEntries.push({
    path: normalizePath(path.relative(repo, configPath)),
    sha256: sha256(configContents),
  });

  const sourceFiles = program
    .getSourceFiles()
    .filter((sourceFile) => {
      const absolutePath = path.resolve(sourceFile.fileName);
      const relativePath = path.relative(repo, absolutePath);
      return !sourceFile.isDeclarationFile &&
        relativePath !== "" &&
        !relativePath.startsWith(`..${path.sep}`) &&
        !path.isAbsolute(relativePath) &&
        !relativePath.split(path.sep).includes("node_modules");
    })
    .sort((left, right) =>
      normalizePath(path.relative(repo, left.fileName)).localeCompare(
        normalizePath(path.relative(repo, right.fileName)),
      )
    );

  const configuredFiles = new Set(
    parsedConfig.fileNames.map((fileName) => path.resolve(fileName)),
  );
  for (const compilerDiagnostic of [
    ...parsedConfig.errors,
    ...program.getSyntacticDiagnostics(),
    ...program.getSemanticDiagnostics(),
  ].filter((candidate) =>
    (!candidate.file || configuredFiles.has(path.resolve(candidate.file.fileName))) &&
    !isMissingTestFrameworkGlobal(candidate)
  )) {
    diagnostics.push({
      code: `TYPESCRIPT_${compilerDiagnostic.code}`,
      severity: "error",
      message: ts.flattenDiagnosticMessageText(compilerDiagnostic.messageText, "\n"),
    });
  }

  for (const sourceFile of sourceFiles) {
    const sourcePath = path.resolve(sourceFile.fileName);
    const sourceStat = await lstat(sourcePath);
    const realSourcePath = await realpath(sourcePath);
    if (sourceStat.isSymbolicLink() || !isWithin(repo, realSourcePath)) {
      diagnostics.push({
        code: "SOURCE_PATH_ESCAPE",
        severity: "error",
        message: `Source uses a symlink or escapes repository: ${sourceFile.fileName}`,
      });
      continue;
    }
    const contents = sourceFile.text;
    const context: SourceContext = {
      repo,
      file: sourceFile,
      relativePath: normalizePath(path.relative(repo, realSourcePath)),
      contents,
      sha256: sha256(contents),
    };
    repositoryEntries.push({
      path: context.relativePath,
      sha256: context.sha256,
    });
    const extracted = extractSourceFile(checker, context);
    facts.push(...extracted.facts);
    diagnostics.push(...extracted.diagnostics);
  }

  for (
    const absoluteContractPath of (options.contracts ?? [])
      .map((contractPath) =>
        path.resolve(repo, contractPath)
      )
      .sort((left, right) => left.localeCompare(right))
  ) {
    const contractStat = await lstat(absoluteContractPath);
    const realContractPath = await realpath(absoluteContractPath);
    if (contractStat.isSymbolicLink() || !isWithin(repo, realContractPath)) {
      diagnostics.push({
        code: "CONTRACT_PATH_ESCAPE",
        severity: "error",
        message: `Contract uses a symlink or escapes repository: ${absoluteContractPath}`,
      });
      continue;
    }
    const contents = await readFile(realContractPath, "utf8");
    const relativePath = normalizePath(path.relative(repo, realContractPath));
    repositoryEntries.push({ path: relativePath, sha256: sha256(contents) });
    const extracted = contractFacts(repo, realContractPath, contents);
    facts.push(...extracted.facts);
    diagnostics.push(...extracted.diagnostics);
  }

  repositoryEntries.sort((left, right) => left.path.localeCompare(right.path));
  const repositorySha256 = sha256(
    repositoryEntries
      .map((entry) => `${entry.path}\0${entry.sha256}`)
      .join("\n"),
  );

  return {
    schemaVersion: "1.0",
    language: "typescript",
    extractor: { name: "typescript-compiler-api", version: "6.0.3" },
    repositorySha256,
    facts: facts.sort(compareFacts),
    diagnostics: diagnostics.sort(compareDiagnostics),
  };
}

function isMissingTestFrameworkGlobal(
  diagnostic: ts.Diagnostic,
): boolean {
  if (!diagnostic.file || !TEST_FILE.test(normalizePath(diagnostic.file.fileName))) {
    return false;
  }
  if (diagnostic.code !== 2304 && diagnostic.code !== 2593) {
    return false;
  }
  const message = ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n");
  return /Cannot find name '(?:test|it|describe|expect|beforeEach|afterEach)'/u.test(message);
}

function isWithin(root: string, candidate: string): boolean {
  const relative = path.relative(root, candidate);
  return relative === "" ||
    (!relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}
