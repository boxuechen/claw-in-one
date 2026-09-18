#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const [bundledRootInput, productRootInput] = process.argv.slice(2);
if (!bundledRootInput || !productRootInput) {
  throw new Error(
    "usage: install-bundled-product-plugins.mjs <bundled-root> <product-root>",
  );
}

const products = new Map([
  ["android-use", "claw-in-one-android-use"],
  ["vscreen-foundation", "claw-in-one-vscreen-foundation"],
  ["android-developer-bridge", "claw-in-one-android-developer-bridge"],
  ["web-development", "claw-in-one-web-development"],
  ["project-workspaces", "claw-in-one-project-workspaces"],
]);

const requireDirectory = (value, name) => {
  const resolved = fs.realpathSync(value);
  if (!fs.statSync(resolved).isDirectory()) {
    throw new Error(`${name} must be a directory`);
  }
  return resolved;
};

const bundledRoot = requireDirectory(bundledRootInput, "bundled root");
const productRoot = requireDirectory(productRootInput, "product root");

function inventory(root) {
  const files = new Map();
  const visit = (directory, prefix = "") => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
      const absolute = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) {
        throw new Error(`Product Plugin cannot contain a symlink: ${relative}`);
      }
      if (entry.isDirectory()) {
        visit(absolute, relative);
        continue;
      }
      if (!entry.isFile()) {
        throw new Error(`Product Plugin contains an unsupported entry: ${relative}`);
      }
      files.set(
        relative,
        crypto.createHash("sha256").update(fs.readFileSync(absolute)).digest("hex"),
      );
    }
  };
  visit(root);
  return files;
}

function inventoriesMatch(left, right) {
  if (left.size !== right.size) return false;
  for (const [name, digest] of left) {
    if (right.get(name) !== digest) return false;
  }
  return true;
}

for (const [directoryName, pluginId] of products) {
  const source = path.join(productRoot, directoryName);
  if (!fs.statSync(source).isDirectory()) {
    throw new Error(`Missing Product Plugin: ${directoryName}`);
  }
  const manifest = JSON.parse(fs.readFileSync(path.join(source, "openclaw.plugin.json"), "utf8"));
  if (manifest.id !== pluginId) {
    throw new Error(`Unexpected Product Plugin identity: ${directoryName}`);
  }

  const destination = path.join(bundledRoot, directoryName);
  const sourceInventory = inventory(source);
  if (fs.existsSync(destination)) {
    const stat = fs.lstatSync(destination);
    if (!stat.isDirectory() || stat.isSymbolicLink()) {
      throw new Error(`Bundled Product Plugin target is not a directory: ${directoryName}`);
    }
    if (inventoriesMatch(sourceInventory, inventory(destination))) continue;
  }

  const suffix = `${process.pid}-${crypto.randomUUID()}`;
  const staging = path.join(bundledRoot, `.claw-in-one-${directoryName}.next-${suffix}`);
  const previous = path.join(bundledRoot, `.claw-in-one-${directoryName}.previous-${suffix}`);
  let movedPrevious = false;
  try {
    fs.cpSync(source, staging, {
      recursive: true,
      errorOnExist: true,
      force: false,
      preserveTimestamps: true,
    });
    if (!inventoriesMatch(sourceInventory, inventory(staging))) {
      throw new Error(`Product Plugin staging verification failed: ${directoryName}`);
    }
    if (fs.existsSync(destination)) {
      fs.renameSync(destination, previous);
      movedPrevious = true;
    }
    fs.renameSync(staging, destination);
    if (movedPrevious) fs.rmSync(previous, { recursive: true, force: true });
  } catch (error) {
    if (!fs.existsSync(destination) && movedPrevious && fs.existsSync(previous)) {
      fs.renameSync(previous, destination);
    }
    fs.rmSync(staging, { recursive: true, force: true });
    throw error;
  }
}
