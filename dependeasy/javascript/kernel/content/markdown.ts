import { unified } from 'unified';
import parse from 'remark-parse';
import gfm from 'remark-gfm';
import frontmatter from 'remark-frontmatter';
import directive from 'remark-directive';
import rehype from 'remark-rehype';
import raw from 'rehype-raw';
import stringify from 'rehype-stringify';
import { visit } from 'unist-util-visit';
import type { Root } from 'mdast';
import headings from './headings.ts';

function admonitions() {
  return (tree: Root) => {
    visit(tree, 'containerDirective', node => {
      node.data = { hName: 'aside', hProperties: { className: ['admonition', `admonition-${node.name}`] } };
      if (!node.children[0]?.data?.directiveLabel) node.children.unshift({ type: 'paragraph', children: [
        { type: 'strong', children: [{ type: 'text', value: node.attributes?.title ?? node.name }] } ] });
    });
  };
}

export async function renderMarkdown(source: string): Promise<string> {
  return String(await unified().use(parse).use(gfm).use(frontmatter).use(directive).use(admonitions).use(headings)
    .use(rehype, { allowDangerousHtml: true }).use(raw).use(stringify).process(source));
}
