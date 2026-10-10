import * as pagefind from 'pagefind';
import { mkdir, mkdtemp, rename, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';

function checked(result) {
  if (result.errors?.length) throw new Error(result.errors.join('\n'));
  return result;
}

export async function buildPagefind(records, outputPath) {
  if (!records.length) throw new Error('A Pagefind index needs at least one record');
  const destination = resolve(outputPath);
  await mkdir(dirname(destination), { recursive: true });
  const staging = await mkdtemp(join(dirname(destination), '.pagefind-'));
  let index;
  try {
    ({ index } = checked(await pagefind.createIndex()));
    for (const record of records) checked(await index.addCustomRecord(record));
    checked(await index.writeFiles({ outputPath: staging }));
    await rm(destination, { recursive: true, force: true });
    await rename(staging, destination);
  } finally {
    if (index) checked(await index.deleteIndex());
    await rm(staging, { recursive: true, force: true });
  }
}

export function documentationRecords(documents, corpusRevision) {
  if (typeof corpusRevision !== 'string' || !corpusRevision) throw new Error('Corpus revision is required');
  return documents.filter(doc => doc.visibility === 'public').flatMap(doc => {
    const url = new URL(doc.url);
    if (!['https:', 'http:'].includes(url.protocol)) throw new Error(`Invalid document URL: ${doc.id}`);
    return doc.sections.filter(section => section.text?.trim()).map(section => {
      const sectionUrl = new URL(url);
      sectionUrl.hash = section.anchor;
      return {
        url: sectionUrl.href, content: `${doc.title}\n${section.title}\n${section.text}`, language: 'en',
        meta: {
          title: `${doc.title} — ${section.title}`, document: doc.id, section: section.id,
          revision: doc.revision, corpusRevision, status: doc.status ?? '',
          verification: JSON.stringify(doc.verification ?? {}), sources: JSON.stringify(doc.sources ?? []),
        },
        filters: { kind: [doc.kind ?? 'document'], group: [doc.group ?? 'general'], status: [doc.status ?? 'unknown'] },
      };
    });
  });
}

export const closePagefind = () => pagefind.close();
