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
  severity: "warning" | "error";
  message: string;
  source?: SourceReference;
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
