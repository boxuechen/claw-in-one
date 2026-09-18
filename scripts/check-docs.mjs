import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const withoutFences = text => text.replace(/^```[^\n]*\n[\s\S]*?^```\s*$/gm, '');
export function headingIds(markdown) {
  const counts = new Map();
  return [...withoutFences(markdown).matchAll(/^#{1,6} (.+?)\s*#*$/gm)].map((match) => {
    const base = match[1].toLowerCase().replace(/[^\p{L}\p{N}_\-\s]/gu, '').replace(/ /g, '-');
    const count = counts.get(base) ?? 0;
    counts.set(base, count + 1);
    return count ? `${base}-${count}` : base;
  });
}
export function checkLinks(root, files) {
  const errors = [];
  let checked = 0;
  for (const file of files) {
    const text = withoutFences(fs.readFileSync(path.join(root, file), 'utf8'));
    for (const match of text.matchAll(/\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+"[^"]*")?\)/g)) {
      const href = match[1].replace(/^<|>$/g, '');
      if (/^[a-z][a-z0-9+.-]*:/i.test(href)) continue;
      const [relative, anchor] = href.split('#');
      try {
        const target = relative ? path.resolve(root, path.dirname(file), decodeURIComponent(relative)) : path.resolve(root, file);
        if (!fs.existsSync(target)) throw new Error('missing file');
        if (anchor && target.endsWith('.md') && !headingIds(fs.readFileSync(target, 'utf8')).includes(decodeURIComponent(anchor))) throw new Error('missing heading');
        checked++;
      } catch (error) { errors.push(`${file}: ${href} (${error.message})`); }
    }
  }
  return {checked, errors};
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const files = [
    ...fs.readdirSync(root).filter((name) => name.endsWith('.md')),
    ...fs.readdirSync(path.join(root, 'docs')).filter((name) => name.endsWith('.md')).map((name) => `docs/${name}`),
    'apps/android/AGENTS.md', 'apps/android/style.md', 'ref/README.md',
  ];
  const result = checkLinks(root, files);
  for (const error of result.errors) console.error(error);
  console.log(`${result.checked} local links checked; ${result.errors.length} errors.`);
  process.exitCode = result.errors.length ? 1 : 0;
}
