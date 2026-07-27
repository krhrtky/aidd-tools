import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { extractRepository } from "../src/extractor.js";
import type { CodeFact, ExtractionResult } from "../src/types.js";

async function fixture(files: Record<string, string>): Promise<string> {
  const root = await mkdtemp(path.join(tmpdir(), "aidd-ts-extractor-"));
  await Promise.all(
    Object.entries(files).map(async ([relativePath, contents]) => {
      const absolutePath = path.join(root, relativePath);
      await mkdir(path.dirname(absolutePath), { recursive: true });
      await writeFile(absolutePath, contents, "utf8");
    }),
  );
  return root;
}

function fact(
  result: ExtractionResult,
  kind: string,
  name: string,
): CodeFact {
  const found = result.facts.find(
    (candidate) => candidate.kind === kind && candidate.name === name,
  );
  assert.ok(found, `missing ${kind} fact ${name}`);
  return found;
}

test("extracts exported declarations, behavior, tests, and AIDD links", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: {
        strict: true,
        target: "ES2022",
        module: "NodeNext",
        moduleResolution: "NodeNext",
      },
      include: ["src/**/*.ts"],
    }),
    "src/payment.ts": `
export interface User {
  id: string;
  email: string | null;
}

export enum PaymentState {
  Pending = "pending",
  Paid = "paid"
}

export type PaymentResult =
  | { kind: "success"; receiptId: string }
  | { kind: "failure"; reason: string };

function internalOnly(): void {}

/** @aidd.requirement REQ-PAY-1
 * @aidd.verifies INV-BALANCE
 */
export function charge(user: User | null, amount: number): PaymentResult {
  if (user === null || amount <= 0) {
    throw new RangeError("invalid");
  }
  let state = PaymentState.Pending;
  state = PaymentState.Paid;
  internalOnly();
  return { kind: "success", receiptId: user.id };
}
`,
    "src/payment.test.ts": `
import { charge } from "./payment.js";
test("charge", () => {
  expect(charge({ id: "u1", email: null }, 10).kind).toBe("success");
});
`,
  });

  const result = await extractRepository({ repo: root });

  assert.equal(result.schemaVersion, "1.0");
  assert.equal(result.language, "typescript");
  assert.match(result.repositorySha256, /^[a-f0-9]{64}$/);

  const user = fact(result, "interface", "User");
  assert.equal(user.status, "accepted");
  assert.equal(user.basis, "observed");
  assert.equal(user.source.path, "src/payment.ts");
  assert.match(user.source.sha256, /^[a-f0-9]{64}$/);
  assert.equal(user.details.exported, true);

  const state = fact(result, "enum", "PaymentState");
  assert.deepEqual(state.details.members, [
    { name: "Pending", value: "pending" },
    { name: "Paid", value: "paid" },
  ]);

  const union = fact(result, "discriminatedUnion", "PaymentResult");
  assert.equal(union.details.discriminator, "kind");
  assert.deepEqual(union.details.variants, [
    { discriminatorValue: "success", type: '{ kind: "success"; receiptId: string; }' },
    { discriminatorValue: "failure", type: '{ kind: "failure"; reason: string; }' },
  ]);

  const charge = fact(result, "function", "charge");
  assert.deepEqual(charge.details.parameters, [
    { name: "user", nullable: true, optional: false, type: "User | null" },
    { name: "amount", nullable: false, optional: false, type: "number" },
  ]);
  assert.equal(charge.details.returnType, "PaymentResult");
  assert.deepEqual(charge.details.requirementIds, ["REQ-PAY-1"]);
  assert.deepEqual(charge.details.verifiesIds, ["INV-BALANCE"]);
  assert.equal(
    result.facts.some(
      (candidate) =>
        candidate.kind === "function" && candidate.name === "internalOnly",
    ),
    false,
  );

  fact(result, "guard", "user === null || amount <= 0");
  fact(result, "throw", 'new RangeError("invalid")');
  fact(result, "assignment", "state");
  fact(result, "call", "internalOnly");
  fact(result, "testAssertion", "toBe");
  assert.deepEqual(result.diagnostics, []);
});

test("reports unresolved production types and rejects external contract paths", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({ include: ["src/**/*.ts"] }),
    "src/api.ts": "export function load(value: MissingType): MissingType { return value; }\n",
  });
  const outside = path.join(await mkdtemp(path.join(tmpdir(), "aidd-outside-")), "schema.json");
  await writeFile(outside, JSON.stringify({ type: "object" }), "utf8");

  const result = await extractRepository({ repo: root, contracts: [outside] });

  assert.ok(result.diagnostics.some((diagnostic) => diagnostic.severity === "error"));
  assert.ok(result.diagnostics.some((diagnostic) => diagnostic.code === "CONTRACT_PATH_ESCAPE"));
});

test("does not accept source files reached through a symlink", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({ include: ["src/**/*.ts"] }),
  });
  const outside = path.join(await mkdtemp(path.join(tmpdir(), "aidd-outside-")), "outside.ts");
  await writeFile(outside, "export interface Secret { value: string }\n", "utf8");
  await mkdir(path.join(root, "src"), { recursive: true });
  await symlink(outside, path.join(root, "src", "linked.ts"));

  const result = await extractRepository({ repo: root });

  assert.equal(result.facts.some((candidate) => candidate.name === "Secret"), false);
  assert.ok(result.diagnostics.some((diagnostic) => diagnostic.code === "SOURCE_PATH_ESCAPE"));
});

test("is byte-stable and reports dynamic or unresolved calls without accepting them", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: { strict: true, target: "ES2022", module: "NodeNext" },
      include: ["src/**/*.ts"],
    }),
    "src/dynamic.ts": `
export function invoke(target: unknown): unknown {
  return (target as any).run();
}
`,
  });

  const first = await extractRepository({ repo: root });
  const second = await extractRepository({ repo: root });

  assert.deepEqual(first, second);
  assert.equal(first.facts.some((candidate) => candidate.kind === "call"), false);
  assert.equal(first.diagnostics.length, 1);
  assert.equal(first.diagnostics[0]?.code, "DYNAMIC_CALL");
  assert.equal(first.diagnostics[0]?.source?.path, "src/dynamic.ts");
});

test("extracts OpenAPI and JSON Schema contracts deterministically", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: { strict: true },
      include: ["src/**/*.ts"],
    }),
    "src/index.ts": "export const version: string = '1';\n",
    "contracts/openapi.yaml": `
openapi: 3.1.0
info:
  title: Payments
  version: 1.0.0
paths:
  /payments:
    post:
      operationId: createPayment
      responses:
        "201":
          description: created
`,
    "contracts/payment.schema.json": JSON.stringify({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      $id: "Payment",
      type: "object",
      required: ["amount"],
      properties: {
        amount: { type: "number", minimum: 0 },
      },
    }),
  });

  const result = await extractRepository({
    repo: root,
    contracts: [
      path.join(root, "contracts/openapi.yaml"),
      path.join(root, "contracts/payment.schema.json"),
    ],
  });

  const operation = fact(result, "openApiOperation", "createPayment");
  assert.equal(operation.details.method, "POST");
  assert.equal(operation.details.path, "/payments");

  const schema = fact(result, "jsonSchema", "Payment");
  assert.equal(schema.details.type, "object");
  assert.deepEqual(schema.details.required, ["amount"]);
  assert.deepEqual(schema.details.properties, ["amount"]);
});

test("treats exported arrow functions as functions and reads tests excluded by tsconfig", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: { strict: true, target: "ES2022" },
      include: ["src/**/*.ts"],
      exclude: ["test"],
    }),
    "src/lookup.ts": `
export const lookup = (id: string | null): string | undefined => {
  if (id === null) return undefined;
  return id;
};
`,
    "test/lookup.spec.ts": `
import { lookup } from "../src/lookup";
test("lookup", () => {
  expect(lookup("known")).toBe("known");
});
`,
  });

  const result = await extractRepository({ repo: root });

  const lookup = fact(result, "function", "lookup");
  assert.deepEqual(lookup.details.parameters, [
    { name: "id", nullable: true, optional: false, type: "string | null" },
  ]);
  assert.equal(lookup.details.returnType, "string | undefined");
  assert.equal(lookup.details.nullableReturn, true);
  const assertion = fact(result, "testAssertion", "toBe");
  assert.equal(assertion.source.path, "test/lookup.spec.ts");
});

test("normalizes an annotated pure bigint Result function into observed contract IR", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: {
        strict: true,
        target: "ES2022",
        module: "NodeNext",
        moduleResolution: "NodeNext",
      },
      include: ["src/**/*.ts"],
    }),
    "src/withdraw.ts": `
export type WithdrawResult =
  | { ok: true; value: bigint }
  | { ok: false; error: "InvalidBalance" | "InvalidAmount" | "InsufficientFunds" };

/** @aidd.contract urn:aidd:withdraw:contract:withdraw */
export function withdraw(balance: bigint, amount: bigint): WithdrawResult {
  if (balance < 0n) return { ok: false, error: "InvalidBalance" };
  if (amount <= 0n) return { ok: false, error: "InvalidAmount" };
  if (balance < amount) return { ok: false, error: "InsufficientFunds" };
  return { ok: true, value: balance - amount };
}
`,
  });

  const result = await extractRepository({ repo: root });
  const contract = fact(result, "observedContract", "withdraw");

  assert.deepEqual(contract.details, {
    schemaVersion: "1.0",
    contractIds: ["urn:aidd:withdraw:contract:withdraw"],
    operation: "src/withdraw.ts#withdraw",
    parameters: [
      { name: "balance", valueType: { kind: "int" } },
      { name: "amount", valueType: { kind: "int" } },
    ],
    resultType: { kind: "int" },
    errorTypes: ["InsufficientFunds", "InvalidAmount", "InvalidBalance"],
    cases: [
      {
        when: {
          op: "lt",
          args: [{ op: "valueRef", name: "balance" }, { op: "intLiteral", value: "0" }],
        },
        outcome: { kind: "error", error: "InvalidBalance" },
      },
      {
        when: {
          op: "lte",
          args: [{ op: "valueRef", name: "amount" }, { op: "intLiteral", value: "0" }],
        },
        outcome: { kind: "error", error: "InvalidAmount" },
      },
      {
        when: {
          op: "lt",
          args: [{ op: "valueRef", name: "balance" }, { op: "valueRef", name: "amount" }],
        },
        outcome: { kind: "error", error: "InsufficientFunds" },
      },
      {
        when: { op: "literal", value: true },
        outcome: {
          kind: "success",
          value: {
            op: "sub",
            args: [{ op: "valueRef", name: "balance" }, { op: "valueRef", name: "amount" }],
          },
        },
      },
    ],
  });
  assert.deepEqual(result.diagnostics, []);
});

test("fails closed for an annotated contract with an arbitrary external call", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: { strict: true, target: "ES2022" },
      include: ["src/**/*.ts"],
    }),
    "src/unsafe.ts": `
declare function externalValue(): bigint;
/** @aidd.contract urn:aidd:unsafe:contract */
export function unsafe(value: bigint): { ok: true; value: bigint } {
  return { ok: true, value: value + externalValue() };
}
`,
  });

  const result = await extractRepository({ repo: root });

  assert.equal(result.facts.some((candidate) => candidate.kind === "observedContract"), false);
  assert.ok(
    result.diagnostics.some(
      (diagnostic) =>
        diagnostic.code === "UNSUPPORTED_OBSERVED_CONTRACT" &&
        diagnostic.severity === "unsupported",
    ),
  );
});

test("normalizes a complete if else result without semantic guessing", async () => {
  const root = await fixture({
    "tsconfig.json": JSON.stringify({
      compilerOptions: { strict: true, target: "ES2022" },
      include: ["src/**/*.ts"],
    }),
    "src/sign.ts": `
type SignResult =
  | { ok: true; value: bigint }
  | { ok: false; error: "Negative" };

/** @aidd.contract urn:aidd:sign:contract */
export function sign(value: bigint): SignResult {
  if (value < 0n) {
    return { ok: false, error: "Negative" };
  } else {
    return { ok: true, value: value };
  }
}
`,
  });

  const result = await extractRepository({ repo: root });
  assert.deepEqual(result.diagnostics, []);
  const contract = fact(result, "observedContract", "sign");

  assert.deepEqual((contract.details as { cases: unknown[] }).cases, [
    {
      when: {
        op: "lt",
        args: [{ op: "valueRef", name: "value" }, { op: "intLiteral", value: "0" }],
      },
      outcome: { kind: "error", error: "Negative" },
    },
    {
      when: { op: "literal", value: true },
      outcome: { kind: "success", value: { op: "valueRef", name: "value" } },
    },
  ]);
});
