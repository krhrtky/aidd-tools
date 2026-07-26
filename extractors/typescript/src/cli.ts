#!/usr/bin/env node

import { lstat, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { extractRepository } from "./extractor.js";

interface CliOptions {
  repo: string;
  out: string;
  contracts: string[];
}

function usage(): string {
  return [
    "Usage: aidd-typescript-extractor --repo <path> --out <file> [--contracts <path> ...]",
    "",
    "Reads tsconfig.json and source files statically. Target code is never executed.",
  ].join("\n");
}

function parseArguments(argv: string[]): CliOptions {
  let repo: string | undefined;
  let out: string | undefined;
  const contracts: string[] = [];

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (argument === "--repo" && value) {
      repo = value;
      index += 1;
      continue;
    }
    if (argument === "--out" && value) {
      out = value;
      index += 1;
      continue;
    }
    if (argument === "--contracts" && value) {
      contracts.push(...value.split(",").filter(Boolean));
      index += 1;
      continue;
    }
    if (argument === "--help" || argument === "-h") {
      process.stdout.write(`${usage()}\n`);
      process.exit(0);
    }
    throw new Error(`Unknown or incomplete argument: ${argument ?? ""}`);
  }

  if (!repo || !out) {
    throw new Error(usage());
  }
  return { repo, out, contracts };
}

async function main(): Promise<void> {
  const options = parseArguments(process.argv.slice(2));
  const result = await extractRepository({
    repo: options.repo,
    contracts: options.contracts,
  });
  const outputPath = path.resolve(options.out);
  await mkdir(path.dirname(outputPath), { recursive: true });
  await rejectSymlinkOutput(outputPath);
  await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`, "utf8");
  if (result.diagnostics.some((diagnostic) => diagnostic.severity === "error")) {
    process.exitCode = 4;
  }
}

async function rejectSymlinkOutput(outputPath: string): Promise<void> {
  const parsed = path.parse(outputPath);
  const components = outputPath.slice(parsed.root.length).split(path.sep).filter(Boolean);
  let current = parsed.root;
  for (let index = 0; index < components.length; index += 1) {
    current = path.join(current, components[index]);
    try {
      const entry = await lstat(current);
      if (index > 0 && entry.isSymbolicLink()) {
        throw new Error(`Output path contains a symbolic link: ${current}`);
      }
    } catch (error: unknown) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") {
        continue;
      }
      throw error;
    }
  }
}

main().catch((error: unknown) => {
  process.stderr.write(
    `${error instanceof Error ? error.message : String(error)}\n`,
  );
  process.exitCode = 1;
});
