import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

export const DEFAULT_CONFIG_PATH = "project-docs.config.json";

const DOCUMENT_KEY_PATTERN = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$|^[a-z]$/;
const BRANCH_PATTERN = /^(?!refs\/)(?!\/)(?!.*(?:\.\.|\/\/|@\{|\\))[^\s~^:?*\[]+$/;

function fail(message) {
  throw new Error(`Invalid project docs configuration: ${message}`);
}

function normalizedRelativePath(value, fieldName) {
  if (typeof value !== "string" || value.trim().length === 0) {
    fail(`${fieldName} must be a non-empty string.`);
  }

  const candidate = value.trim().replaceAll("\\", "/");
  if (
    path.posix.isAbsolute(candidate) ||
    /^[A-Za-z]:\//.test(candidate) ||
    candidate.split("/").includes("..")
  ) {
    fail(`${fieldName} must stay inside the repository.`);
  }

  const normalized = path.posix.normalize(candidate).replace(/^\.\//, "");
  if (normalized === "." || normalized.startsWith("../")) {
    fail(`${fieldName} must stay inside the repository.`);
  }
  return normalized;
}

function stringArray(value, fieldName) {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string" || !item.trim())) {
    fail(`${fieldName} must be an array of non-empty strings.`);
  }
  return value.map((item) => item.trim());
}

export function validateProjectDocsConfig(rawConfig) {
  if (!rawConfig || typeof rawConfig !== "object" || Array.isArray(rawConfig)) {
    fail("the root value must be an object.");
  }
  const rootFields = new Set([
    "$schema",
    "version",
    "canonicalBranch",
    "publicationMode",
    "documents",
  ]);
  const unknownRootFields = Object.keys(rawConfig).filter((field) => !rootFields.has(field));
  if (unknownRootFields.length > 0) {
    fail(`unknown root field(s): ${unknownRootFields.join(", ")}.`);
  }
  if (rawConfig.version !== 1) {
    fail('version must be 1.');
  }

  const canonicalBranch = rawConfig.canonicalBranch ?? "main";
  if (
    typeof canonicalBranch !== "string" ||
    !BRANCH_PATTERN.test(canonicalBranch) ||
    canonicalBranch.endsWith(".") ||
    canonicalBranch.endsWith("/")
  ) {
    fail("canonicalBranch is not a valid Git branch name.");
  }

  const publicationMode = rawConfig.publicationMode ?? "manual";
  if (!["manual", "on-main-push"].includes(publicationMode)) {
    fail('publicationMode must be "manual" or "on-main-push".');
  }

  if (
    !Array.isArray(rawConfig.documents) ||
    rawConfig.documents.length === 0 ||
    rawConfig.documents.length > 20
  ) {
    fail("documents must contain between 1 and 20 entries.");
  }

  const keys = new Set();
  const paths = new Set();
  const documents = rawConfig.documents.map((rawDocument, index) => {
    const field = `documents[${index}]`;
    if (!rawDocument || typeof rawDocument !== "object" || Array.isArray(rawDocument)) {
      fail(`${field} must be an object.`);
    }
    const documentFields = new Set(["key", "label", "path", "requiredSections"]);
    const unknownDocumentFields = Object.keys(rawDocument).filter(
      (name) => !documentFields.has(name),
    );
    if (unknownDocumentFields.length > 0) {
      fail(`${field} has unknown field(s): ${unknownDocumentFields.join(", ")}.`);
    }

    const key = rawDocument.key;
    if (typeof key !== "string" || !DOCUMENT_KEY_PATTERN.test(key)) {
      fail(`${field}.key must use lower-case letters, digits, and hyphens.`);
    }
    if (keys.has(key)) fail(`${field}.key duplicates "${key}".`);
    keys.add(key);

    const label = rawDocument.label;
    if (typeof label !== "string" || !label.trim() || label.length > 100) {
      fail(`${field}.label must be a non-empty string of at most 100 characters.`);
    }

    const repositoryPath = normalizedRelativePath(rawDocument.path, `${field}.path`);
    if (!repositoryPath.toLocaleLowerCase().endsWith(".md")) {
      fail(`${field}.path must point to a Markdown file.`);
    }
    const comparablePath = repositoryPath.toLocaleLowerCase();
    if (paths.has(comparablePath)) fail(`${field}.path duplicates "${repositoryPath}".`);
    paths.add(comparablePath);

    return {
      key,
      label: label.trim(),
      repositoryPath,
      requiredSections: stringArray(rawDocument.requiredSections, `${field}.requiredSections`),
    };
  });

  return {
    version: 1,
    canonicalBranch,
    publicationMode,
    documents,
  };
}

export function loadProjectDocsConfig({
  rootDirectory = process.cwd(),
  configPath = DEFAULT_CONFIG_PATH,
} = {}) {
  const root = path.resolve(rootDirectory);
  const absoluteConfigPath = path.resolve(root, configPath);
  const relativeConfigPath = path.relative(root, absoluteConfigPath).replaceAll("\\", "/");
  if (relativeConfigPath.startsWith("../") || path.isAbsolute(relativeConfigPath)) {
    fail("configPath must stay inside the repository.");
  }
  if (!existsSync(absoluteConfigPath)) {
    throw new Error(`Project docs configuration does not exist: ${relativeConfigPath}`);
  }

  let parsed;
  try {
    parsed = JSON.parse(readFileSync(absoluteConfigPath, "utf8"));
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    throw new Error(`Cannot parse ${relativeConfigPath}: ${reason}`);
  }

  return {
    ...validateProjectDocsConfig(parsed),
    configPath: absoluteConfigPath,
    repositoryConfigPath: relativeConfigPath,
    rootDirectory: root,
  };
}
