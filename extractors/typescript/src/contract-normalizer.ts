import ts from "typescript";

import type {
  ObservedCase,
  ObservedContract,
  ObservedExpression,
  ObservedOutcome,
  ObservedValueType,
} from "./types.js";

export class UnsupportedObservedContract extends Error {}

export function normalizeObservedContract(
  checker: ts.TypeChecker,
  declaration:
    | ts.FunctionDeclaration
    | ts.MethodDeclaration
    | ts.ArrowFunction
    | ts.FunctionExpression,
  operation: string,
  contractIds: string[],
): ObservedContract {
  if (hasModifier(declaration, ts.SyntaxKind.AsyncKeyword)) {
    unsupported("async functions are outside the pure contract subset");
  }
  if (!declaration.type) {
    unsupported("an explicit return type is required");
  }
  const parameterNames = new Set<string>();
  const parameters = declaration.parameters.map((parameter) => {
    if (!ts.isIdentifier(parameter.name) || !parameter.type || parameter.questionToken || parameter.initializer) {
      unsupported("parameters require identifier names and explicit non-optional types");
    }
    parameterNames.add(parameter.name.text);
    return {
      name: parameter.name.text,
      valueType: normalizeType(checker, checker.getTypeAtLocation(parameter), parameter),
    };
  });
  const signature = checker.getSignatureFromDeclaration(declaration);
  if (!signature) {
    unsupported("the function signature could not be resolved");
  }
  const returnType = checker.getReturnTypeOfSignature(signature);
  const resultShape = normalizeResultType(checker, returnType, declaration);
  const body = declaration.body;
  if (!body) {
    unsupported("a function body is required");
  }
  const cases = normalizeBody(body, parameterNames);
  const observedErrors = cases
    .flatMap((candidate) => candidate.outcome.kind === "error" ? [candidate.outcome.error] : [])
    .sort();
  const declaredErrors = [...resultShape.errorTypes].sort();
  if (JSON.stringify(observedErrors) !== JSON.stringify(declaredErrors)) {
    unsupported("returned error literals must exactly match the declared Result error union");
  }
  return {
    schemaVersion: "1.0",
    contractIds: [...new Set(contractIds)].sort(),
    operation,
    parameters,
    resultType: resultShape.resultType,
    errorTypes: declaredErrors,
    cases,
  };
}

function normalizeResultType(
  checker: ts.TypeChecker,
  returnType: ts.Type,
  location: ts.Node,
): { resultType: ObservedValueType; errorTypes: string[] } {
  const variants = returnType.isUnion() ? returnType.types : [returnType];
  const success = variants.filter((variant) => booleanProperty(checker, variant, "ok", location) === true);
  const failure = variants.filter((variant) => booleanProperty(checker, variant, "ok", location) === false);
  if (success.length !== 1 || failure.length !== 1 || success.length + failure.length !== variants.length) {
    unsupported("return type must be a discriminated {ok:true,value}|{ok:false,error} Result union");
  }
  const valueProperty = success[0].getProperty("value");
  const errorProperty = failure[0].getProperty("error");
  const valueDeclaration = valueProperty?.valueDeclaration ?? valueProperty?.declarations?.[0];
  const errorDeclaration = errorProperty?.valueDeclaration ?? errorProperty?.declarations?.[0];
  if (!valueProperty || !valueDeclaration || !errorProperty || !errorDeclaration) {
    unsupported("Result variants require value and error properties");
  }
  const errorType = checker.getTypeOfSymbolAtLocation(errorProperty, errorDeclaration);
  const errorVariants = errorType.isUnion() ? errorType.types : [errorType];
  const errorTypes = errorVariants.map((variant) => {
    if ((variant.flags & ts.TypeFlags.StringLiteral) === 0) {
      unsupported("Result error must be a string literal union");
    }
    return (variant as ts.StringLiteralType).value;
  });
  return {
    resultType: normalizeType(
      checker,
      checker.getTypeOfSymbolAtLocation(valueProperty, valueDeclaration),
      valueDeclaration,
    ),
    errorTypes,
  };
}

function booleanProperty(
  checker: ts.TypeChecker,
  type: ts.Type,
  name: string,
  location: ts.Node,
): boolean | undefined {
  const property = type.getProperty(name);
  if (!property) return undefined;
  const declaration = property.valueDeclaration ?? property.declarations?.[0] ?? location;
  const propertyType = checker.getTypeOfSymbolAtLocation(property, declaration);
  if ((propertyType.flags & ts.TypeFlags.BooleanLiteral) === 0) return undefined;
  return (propertyType as unknown as { intrinsicName?: string }).intrinsicName === "true";
}

function normalizeType(
  checker: ts.TypeChecker,
  type: ts.Type,
  location: ts.Node,
): ObservedValueType {
  if ((type.flags & ts.TypeFlags.BigIntLike) !== 0) return { kind: "int" };
  if ((type.flags & ts.TypeFlags.BooleanLike) !== 0) return { kind: "bool" };
  if ((type.flags & ts.TypeFlags.StringLike) !== 0) return { kind: "string" };
  if (checker.isArrayType(type) || checker.isTupleType(type)) {
    const element = checker.getTypeArguments(type as ts.TypeReference)[0];
    if (!element) unsupported("list element type could not be resolved");
    return { kind: "list", elementType: normalizeScalarType(checker, element, location) };
  }
  const reference = type as ts.TypeReference;
  const symbolName = type.aliasSymbol?.getName() ?? type.getSymbol()?.getName();
  if (symbolName === "Set" || symbolName === "ReadonlySet") {
    const element = checker.getTypeArguments(reference)[0];
    if (!element) unsupported("set element type could not be resolved");
    return { kind: "set", elementType: normalizeScalarType(checker, element, location) };
  }
  if ((type.flags & ts.TypeFlags.EnumLike) !== 0 || type.isUnion()) {
    const variants = type.isUnion() ? type.types : [type];
    const members = variants.flatMap((variant) => {
      if ((variant.flags & ts.TypeFlags.StringLiteral) !== 0) {
        return [(variant as ts.StringLiteralType).value];
      }
      return [];
    });
    if (members.length === variants.length && members.length > 0) {
      return {
        kind: "enum",
        name: type.aliasSymbol?.getName() ?? checker.typeToString(type, location),
        members: [...members].sort(),
      };
    }
  }
  unsupported(`unsupported value type: ${checker.typeToString(type, location)}`);
}

function normalizeScalarType(
  checker: ts.TypeChecker,
  type: ts.Type,
  location: ts.Node,
): ObservedValueType {
  const normalized = normalizeType(checker, type, location);
  if (normalized.kind === "set" || normalized.kind === "list") {
    unsupported("nested collections are not supported");
  }
  return normalized;
}

function normalizeBody(
  body: ts.ConciseBody,
  parameterNames: Set<string>,
): ObservedCase[] {
  if (!ts.isBlock(body)) {
    return [{
      when: { op: "literal", value: true },
      outcome: { kind: "success", value: normalizeExpression(body, parameterNames) },
    }];
  }
  const cases: ObservedCase[] = [];
  for (const [index, statement] of body.statements.entries()) {
    if (ts.isIfStatement(statement)) {
      const outcome = singleReturnOutcome(statement.thenStatement, parameterNames);
      cases.push({
        when: normalizeExpression(statement.expression, parameterNames),
        outcome,
      });
      if (statement.elseStatement) {
        cases.push(...normalizeElseBranch(statement.elseStatement, parameterNames));
        if (index !== body.statements.length - 1) {
          unsupported("statements after a complete if/else are unreachable in the contract subset");
        }
      }
      continue;
    }
    if (ts.isReturnStatement(statement) && statement.expression && index === body.statements.length - 1) {
      cases.push({
        when: { op: "literal", value: true },
        outcome: normalizeOutcome(statement.expression, parameterNames),
      });
      continue;
    }
    unsupported("function body may contain only ordered if-return guards and one final return");
  }
  if (cases.length === 0 || cases.at(-1)?.when.op !== "literal") {
    unsupported("function requires an unconditional final return");
  }
  return cases;
}

function normalizeElseBranch(
  statement: ts.Statement,
  parameterNames: Set<string>,
): ObservedCase[] {
  if (ts.isIfStatement(statement)) {
    const cases: ObservedCase[] = [{
      when: normalizeExpression(statement.expression, parameterNames),
      outcome: singleReturnOutcome(statement.thenStatement, parameterNames),
    }];
    if (statement.elseStatement) {
      cases.push(...normalizeElseBranch(statement.elseStatement, parameterNames));
    }
    return cases;
  }
  return [{
    when: { op: "literal", value: true },
    outcome: singleReturnOutcome(statement, parameterNames),
  }];
}

function singleReturnOutcome(
  statement: ts.Statement,
  parameterNames: Set<string>,
): ObservedOutcome {
  const candidate = ts.isBlock(statement)
    ? statement.statements.length === 1 ? statement.statements[0] : undefined
    : statement;
  if (!candidate || !ts.isReturnStatement(candidate) || !candidate.expression) {
    unsupported("each guard branch must contain exactly one return");
  }
  return normalizeOutcome(candidate.expression, parameterNames);
}

function normalizeOutcome(
  expression: ts.Expression,
  parameterNames: Set<string>,
): ObservedOutcome {
  if (!ts.isObjectLiteralExpression(expression)) {
    unsupported("Result returns must be object literals");
  }
  const properties = new Map(
    expression.properties.flatMap((property) => {
      if (!ts.isPropertyAssignment(property) || !ts.isIdentifier(property.name)) return [];
      return [[property.name.text, property.initializer] as const];
    }),
  );
  const ok = properties.get("ok");
  if (ok?.kind === ts.SyntaxKind.TrueKeyword) {
    const value = properties.get("value");
    if (!value || properties.size !== 2) unsupported("success Result must contain only ok and value");
    return { kind: "success", value: normalizeExpression(value, parameterNames) };
  }
  if (ok?.kind === ts.SyntaxKind.FalseKeyword) {
    const error = properties.get("error");
    if (!error || !ts.isStringLiteral(error) || properties.size !== 2) {
      unsupported("error Result must contain only ok and a string literal error");
    }
    return { kind: "error", error: error.text };
  }
  unsupported("Result return requires a literal ok discriminator");
}

function normalizeExpression(
  expression: ts.Expression,
  parameterNames: Set<string>,
): ObservedExpression {
  if (ts.isParenthesizedExpression(expression)) {
    return normalizeExpression(expression.expression, parameterNames);
  }
  if (ts.isIdentifier(expression)) {
    if (!parameterNames.has(expression.text)) unsupported(`external value reference: ${expression.text}`);
    return { op: "valueRef", name: expression.text };
  }
  if (ts.isBigIntLiteral(expression)) {
    return { op: "intLiteral", value: expression.text.replace(/n$/u, "") };
  }
  if (ts.isStringLiteral(expression)) return { op: "literal", value: expression.text };
  if (expression.kind === ts.SyntaxKind.TrueKeyword) return { op: "literal", value: true };
  if (expression.kind === ts.SyntaxKind.FalseKeyword) return { op: "literal", value: false };
  if (ts.isPrefixUnaryExpression(expression)) {
    if (expression.operator === ts.SyntaxKind.ExclamationToken) {
      return { op: "not", args: [normalizeExpression(expression.operand, parameterNames)] };
    }
    if (expression.operator === ts.SyntaxKind.MinusToken && ts.isBigIntLiteral(expression.operand)) {
      return { op: "intLiteral", value: `-${expression.operand.text.replace(/n$/u, "")}` };
    }
    unsupported("unsupported prefix operator");
  }
  if (ts.isBinaryExpression(expression)) {
    const operation = binaryOperation(expression.operatorToken.kind);
    return {
      op: operation,
      args: [
        normalizeExpression(expression.left, parameterNames),
        normalizeExpression(expression.right, parameterNames),
      ],
    };
  }
  if (ts.isPropertyAccessExpression(expression) && expression.name.text === "length") {
    return { op: "size", args: [normalizeExpression(expression.expression, parameterNames)] };
  }
  if (ts.isElementAccessExpression(expression) && expression.argumentExpression) {
    return {
      op: "index",
      args: [
        normalizeExpression(expression.expression, parameterNames),
        normalizeExpression(expression.argumentExpression, parameterNames),
      ],
    };
  }
  if (ts.isArrayLiteralExpression(expression)) {
    return {
      op: "listLiteral",
      args: expression.elements.map((element) => normalizeExpression(element, parameterNames)),
    };
  }
  if (ts.isNewExpression(expression) && expression.expression.getText() === "Set") {
    const argument = expression.arguments?.[0];
    if (!argument || !ts.isArrayLiteralExpression(argument)) {
      unsupported("Set construction requires an array literal");
    }
    return {
      op: "setLiteral",
      args: argument.elements.map((element) => normalizeExpression(element, parameterNames)),
    };
  }
  if (ts.isCallExpression(expression) && ts.isPropertyAccessExpression(expression.expression)) {
    const receiver = normalizeExpression(expression.expression.expression, parameterNames);
    const args = expression.arguments.map((argument) => normalizeExpression(argument, parameterNames));
    return whenCollectionCall(expression.expression.name.text, receiver, args);
  }
  unsupported(`unsupported expression: ${expression.getText()}`);
}

function whenCollectionCall(
  name: string,
  receiver: ObservedExpression,
  args: ObservedExpression[],
): ObservedExpression {
  switch (name) {
    case "includes":
    case "has":
      if (args.length !== 1) unsupported(`${name} requires one argument`);
      return { op: "contains", args: [receiver, args[0]] };
    case "concat":
      if (args.length !== 1) unsupported("concat requires one argument");
      return { op: "concat", args: [receiver, args[0]] };
    case "slice":
      if (args.length !== 2) unsupported("slice requires start and end");
      return { op: "slice", args: [receiver, ...args] };
    default:
      unsupported(`arbitrary call is not supported: ${name}`);
  }
}

function binaryOperation(kind: ts.SyntaxKind): string {
  const operations = new Map<ts.SyntaxKind, string>([
    [ts.SyntaxKind.PlusToken, "add"],
    [ts.SyntaxKind.MinusToken, "sub"],
    [ts.SyntaxKind.AsteriskToken, "mul"],
    [ts.SyntaxKind.LessThanToken, "lt"],
    [ts.SyntaxKind.LessThanEqualsToken, "lte"],
    [ts.SyntaxKind.GreaterThanToken, "gt"],
    [ts.SyntaxKind.GreaterThanEqualsToken, "gte"],
    [ts.SyntaxKind.EqualsEqualsEqualsToken, "eq"],
    [ts.SyntaxKind.ExclamationEqualsEqualsToken, "neq"],
    [ts.SyntaxKind.AmpersandAmpersandToken, "and"],
    [ts.SyntaxKind.BarBarToken, "or"],
  ]);
  return operations.get(kind) ?? unsupported("unsupported binary operator");
}

function hasModifier(node: ts.Node, kind: ts.SyntaxKind): boolean {
  return ts.getModifiers(node as ts.HasModifiers)?.some((modifier) => modifier.kind === kind) ?? false;
}

function unsupported(message: string): never {
  throw new UnsupportedObservedContract(message);
}
