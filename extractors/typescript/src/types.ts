export interface SourceReference {
  path: string;
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
  sha256: string;
}

export interface CodeFact {
  id: string;
  kind: string;
  name: string;
  qualifiedName: string;
  status: "accepted";
  basis: "observed";
  source: SourceReference;
  details: Record<string, unknown>;
}

export interface ExtractionDiagnostic {
  code: string;
  severity: "warning" | "error" | "unsupported";
  message: string;
  source?: SourceReference;
}

export type ObservedValueType =
  | { kind: "int" | "bool" | "string" }
  | { kind: "enum"; name: string; members: string[] }
  | { kind: "set" | "list"; elementType: ObservedValueType };

export type ObservedExpression =
  | { op: "literal"; value: boolean | string }
  | { op: "intLiteral"; value: string }
  | { op: "valueRef"; name: string }
  | { op: string; args: ObservedExpression[] };

export type ObservedOutcome =
  | { kind: "success"; value: ObservedExpression }
  | { kind: "error"; error: string };

export interface ObservedCase {
  when: ObservedExpression;
  outcome: ObservedOutcome;
}

export interface ObservedContract {
  schemaVersion: "1.0";
  contractIds: string[];
  operation: string;
  parameters: Array<{ name: string; valueType: ObservedValueType }>;
  resultType: ObservedValueType;
  errorTypes: string[];
  cases: ObservedCase[];
}

export interface ExtractionResult {
  schemaVersion: "1.0";
  language: "typescript";
  extractor: { name: "typescript-compiler-api"; version: "6.0.3" };
  repositorySha256: string;
  facts: CodeFact[];
  diagnostics: ExtractionDiagnostic[];
}

export interface ExtractRepositoryOptions {
  repo: string;
  contracts?: string[];
}
