import Slugger from 'github-slugger';
import { toString } from 'mdast-util-to-string';
import type { Heading, Root } from 'mdast';
import { visit } from 'unist-util-visit';

function explicitId(node: Heading): string | undefined {
  const last = node.children.at(-1);
  if (!last || (last.type !== 'text' && last.type !== 'html')) return;
  const match = last.type === 'text' ? /\s*\{#([^}]+)\}\s*$/.exec(last.value)
    : /^<!--\s*#([^\s]+).*-->$/.exec(last.value);
  if (!match) return;
  if (last.type === 'text') {
    last.value = last.value.slice(0, match.index);
    if (!last.value) node.children.pop();
  } else {
    node.children.pop();
    const previous = node.children.at(-1);
    if (previous?.type === 'text') previous.value = previous.value.trimEnd();
  }
  return match[1].trim();
}

export default function headings() {
  return (tree: Root) => {
    const slugs = new Slugger();
    visit(tree, 'heading', node => {
      const explicit = explicitId(node);
      const data = node.data ??= {};
      const properties = data.hProperties ??= {};
      const existing = properties.id;
      const id = typeof existing === 'string' ? slugs.slug(existing, true)
        : explicit ?? slugs.slug(toString(node.children.filter(child => child.type !== 'html')));
      data.id = properties.id = id;
    });
  };
}
