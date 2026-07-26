import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);

test("CLI writes CodeFacts JSON to --out", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "aidd-ts-cli-"));
  await mkdir(path.join(root, "src"), { recursive: true });
  await writeFile(
    path.join(root, "tsconfig.json"),
    JSON.stringify({ include: ["src/**/*.ts"] }),
    "utf8",
  );
  await writeFile(
    path.join(root, "src/index.ts"),
    "export function ping(): string { return 'pong'; }\n",
    "utf8",
  );
  const out = path.join(root, "code-facts.json");

  await execFileAsync(
    process.execPath,
    [
      "--import",
      "tsx",
      path.resolve("src/cli.ts"),
      "--repo",
      root,
      "--out",
      out,
    ],
    { cwd: path.resolve(".") },
  );

  const parsed = JSON.parse(await readFile(out, "utf8")) as {
    schemaVersion: string;
    facts: Array<{ name: string }>;
  };
  assert.equal(parsed.schemaVersion, "1.0");
  assert.equal(parsed.facts.some((candidate) => candidate.name === "ping"), true);
});
