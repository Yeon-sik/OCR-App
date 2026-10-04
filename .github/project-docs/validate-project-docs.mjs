#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  DEFAULT_CONFIG_PATH,
  loadProjectDocsConfig,
} from "./project-docs-config.mjs";

const SECRET_PATTERNS = [
  /\bsk-[A-Za-z0-9_-]{20,}\b/g,
  /\bntn_[A-Za-z0-9_-]{20,}\b/g,
  /\bgh[pousr]_[A-Za-z0-9]{20,}\b/g,
  /\bAKIA[A-Z0-9]{16}\b/g,
  /\bsecret_[A-Za-z0-9_-]{16,}\b/g,
  /\bBearer\s+[A-Za-z0-9._~+/-]{20,}=*\b/gi,
];

const LOCAL_PATH_PATTERNS = [
  /\b[A-Za-z]:\\Users\\[^\\\s]+/g,
  /\/(?:Users|home)\/[^/\s]+/g,
];

const PLACEHOLDER_PATTERNS = [
  /^#\s+\[[^\]\n]+\]\s*$/gm,
  /\{\{[^}\n]+\}\}/g,
  /\[(?:PROJECT NAME|YYYY[^\]\n]*|WRITE[^\]\n]*|INSERT[^\]\n]*|DESCRIBE[^\]\n]*|ADD[^\]\n]*)\]/gi,
  /\b(?:TODO|TBD|FIXME)\b/g,
];

function parseArguments(argv) {
  const options = {
    configPath: undefined,
    requireTracked: false,
    templates: false,
    files: [],
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--require-tracked") options.requireTracked = true;
    else if (argument === "--templates") options.templates = true;
    else if (argument === "--config") {
      options.configPath = argv[index + 1];
      if (!options.configPath) throw new Error("--config requires a path.");
      index += 1;
    } else if (argument.startsWith("--")) {
      throw new Error(`Unknown option: ${argument}`);
    } else {
      options.files.push(argument);
    }
  }

  if (options.templates && options.files.length === 0) {
    throw new Error("--templates requires at least one template path.");
  }
  if (!options.templates && options.files.length === 0 && !options.configPath) {
    options.configPath = DEFAULT_CONFIG_PATH;
  }
  return options;
}

function findRepositoryRoot(startDirectory) {
  try {
    return execFileSync("git", ["rev-parse", "--show-toplevel"], {
      cwd: startDirectory,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return path.resolve(startDirectory);
  }
}

function sourceLine(text, index) {
  return text.slice(0, index).split("\n").length;
}

function extractHeadings(text) {
  return [...text.matchAll(/^(#{1,6})\s+(.+?)\s*$/gm)].map((match) => ({
    level: match[1].length,
    title: match[2].replace(/[*_`]/g, "").trim(),
    line: sourceLine(text, match.index),
  }));
}

function normalizedHeading(value) {
  return value
    .replace(/^\d+(?:-\d+)?[.)]?\s*/, "")
    .trim()
    .toLocaleLowerCase();
}

function trackedByGit(repositoryRoot, targetPath) {
  const relativeTarget = path.relative(repositoryRoot, targetPath).replaceAll("\\", "/");
  try {
    const output = execFileSync("git", ["ls-files", "--", relativeTarget], {
      cwd: repositoryRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    return output.length > 0;
  } catch {
    return false;
  }
}

function markdownTarget(rawTarget) {
  const trimmed = rawTarget.trim();
  if (trimmed.startsWith("<") && trimmed.includes(">")) {
    return trimmed.slice(1, trimmed.indexOf(">"));
  }
  return trimmed.split(/\s+["'(]/, 1)[0];
}

function validateLinks({
  addIssue,
  filePath,
  repositoryRoot,
  requireTracked,
  templates,
  text,
}) {
  const linkPattern = /(!?)\[([^\]]*)\]\(([^)\n]+)\)/g;

  for (const match of text.matchAll(linkPattern)) {
    const isImage = match[1] === "!";
    const label = match[2].trim();
    const target = markdownTarget(match[3]);
    const line = sourceLine(text, match.index);

    if (isImage && label.length === 0) {
      addIssue("error", filePath, line, "Image alt text must not be empty.");
    }
    if (
      target.length === 0 ||
      target.startsWith("#") ||
      /^(?:https?:|mailto:|tel:|data:)/i.test(target)
    ) {
      continue;
    }
    if (/^[A-Za-z]:[\\/]/.test(target) || target.startsWith("/")) {
      addIssue("error", filePath, line, `Publishable Markdown contains an absolute path: ${target}`);
      continue;
    }
    if (templates) {
      if (target.startsWith("../")) {
        addIssue(
          "error",
          filePath,
          line,
          "Template links must be relative to the generated docs file, not the template location.",
        );
      }
      continue;
    }

    let decodedTarget;
    try {
      decodedTarget = decodeURIComponent(target.split(/[?#]/, 1)[0]);
    } catch {
      addIssue("error", filePath, line, `Link target is not valid URI text: ${target}`);
      continue;
    }

    const resolvedTarget = path.resolve(path.dirname(filePath), decodedTarget);
    if (!existsSync(resolvedTarget)) {
      addIssue("error", filePath, line, `Relative link target does not exist: ${target}`);
    } else if (requireTracked && !trackedByGit(repositoryRoot, resolvedTarget)) {
      addIssue("error", filePath, line, `Relative link target is not tracked by Git: ${target}`);
    }
  }
}

export function validateProjectDocuments(rawOptions = {}) {
  const options = {
    configPath: rawOptions.configPath,
    requireTracked: Boolean(rawOptions.requireTracked),
    templates: Boolean(rawOptions.templates),
    files: rawOptions.files ?? [],
    cwd: path.resolve(rawOptions.cwd ?? process.cwd()),
  };
  const repositoryRoot = findRepositoryRoot(options.cwd);
  const issues = [];
  const addIssue = (severity, filePath, line, message) => {
    issues.push({
      severity,
      file: path.relative(repositoryRoot, filePath).replaceAll("\\", "/"),
      line,
      message,
    });
  };

  let projectConfig;
  if (options.configPath) {
    projectConfig = loadProjectDocsConfig({
      rootDirectory: options.cwd,
      configPath: options.configPath,
    });
    if (options.requireTracked && !trackedByGit(repositoryRoot, projectConfig.configPath)) {
      addIssue("error", projectConfig.configPath, 1, "Configuration file is not tracked by Git.");
    }
  }

  const configuredByPath = new Map(
    (projectConfig?.documents ?? []).map((document) => [
      path.resolve(projectConfig.rootDirectory, document.repositoryPath).toLocaleLowerCase(),
      document,
    ]),
  );
  const files =
    options.files.length > 0
      ? options.files
      : (projectConfig?.documents ?? []).map((document) => document.repositoryPath);

  for (const inputFile of files) {
    const filePath = path.resolve(options.cwd, inputFile);
    if (!existsSync(filePath)) {
      addIssue("error", filePath, 1, "Document does not exist.");
      continue;
    }

    const text = readFileSync(filePath, "utf8");
    const lines = text.split(/\r?\n/);
    const headings = extractHeadings(text);
    const configuredDocument = configuredByPath.get(filePath.toLocaleLowerCase());

    if (options.requireTracked && !trackedByGit(repositoryRoot, filePath)) {
      addIssue("error", filePath, 1, "Published document itself is not tracked by Git.");
    }
    if (text.includes("\uFFFD")) {
      addIssue("error", filePath, 1, "Document contains a Unicode replacement character.");
    }
    if (!text.endsWith("\n")) {
      addIssue("warning", filePath, lines.length, "Document should end with a newline.");
    }
    lines.forEach((lineText, index) => {
      if (/[ \t]+$/.test(lineText)) {
        addIssue("warning", filePath, index + 1, "Trailing whitespace.");
      }
    });

    const h1Headings = headings.filter((heading) => heading.level === 1);
    if (h1Headings.length !== 1) {
      addIssue("error", filePath, 1, `Expected exactly one H1 heading, found ${h1Headings.length}.`);
    }
    for (let index = 1; index < headings.length; index += 1) {
      if (headings[index].level > headings[index - 1].level + 1) {
        addIssue(
          "error",
          filePath,
          headings[index].line,
          `Heading level jumps from H${headings[index - 1].level} to H${headings[index].level}.`,
        );
      }
    }

    for (const requiredSection of configuredDocument?.requiredSections ?? []) {
      const normalizedRequired = normalizedHeading(requiredSection);
      if (!headings.some((heading) => normalizedHeading(heading.title).includes(normalizedRequired))) {
        addIssue("error", filePath, 1, `Required section is missing: ${requiredSection}`);
      }
    }

    for (const secretPattern of SECRET_PATTERNS) {
      for (const match of text.matchAll(secretPattern)) {
        addIssue("error", filePath, sourceLine(text, match.index), "Possible credential or secret value.");
      }
    }
    for (const localPathPattern of LOCAL_PATH_PATTERNS) {
      for (const match of text.matchAll(localPathPattern)) {
        addIssue(
          "error",
          filePath,
          sourceLine(text, match.index),
          "Publishable Markdown contains a user-specific local path.",
        );
      }
    }
    if (!options.templates) {
      for (const placeholderPattern of PLACEHOLDER_PATTERNS) {
        for (const match of text.matchAll(placeholderPattern)) {
          addIssue(
            "error",
            filePath,
            sourceLine(text, match.index),
            `Unresolved placeholder marker: ${match[0]}`,
          );
        }
      }
    }

    validateLinks({
      addIssue,
      filePath,
      repositoryRoot,
      requireTracked: options.requireTracked,
      templates: options.templates,
      text,
    });
  }

  return {
    repositoryRoot,
    issues,
    errorCount: issues.filter((issue) => issue.severity === "error").length,
    warningCount: issues.filter((issue) => issue.severity === "warning").length,
  };
}

function printResult(result) {
  for (const issue of result.issues) {
    console.error(`${issue.severity.toUpperCase()} ${issue.file}:${issue.line} ${issue.message}`);
  }
  const checked = result.errorCount === 0 ? "passed" : "failed";
  console.log(
    `Project document validation ${checked}: ${result.errorCount} error(s), ${result.warningCount} warning(s).`,
  );
}

const isDirectExecution =
  process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);

if (isDirectExecution) {
  try {
    const options = parseArguments(process.argv.slice(2));
    const result = validateProjectDocuments({ ...options, cwd: process.cwd() });
    printResult(result);
    if (result.errorCount > 0) process.exitCode = 1;
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
