import { describe, expect, it } from 'vitest';
import { MAX_RESOURCE_DOCUMENTS, resourceDocumentKey, updateDocument, type ResourceDocument } from './resourceWorkspaces';
describe('resource workspaces', () => {
  it('isolates matching names across schemas, connections and view kinds', () => {
    const keys = [resourceDocumentKey(1, 'table', 'a', 't'), resourceDocumentKey(2, 'table', 'a', 't'), resourceDocumentKey(1, 'table', 'b', 't'), resourceDocumentKey(1, 'object', 'a', 't')];
    expect(new Set(keys).size).toBe(4);
  });
  it('updates existing tabs without evicting dirty tabs at the limit', () => {
    const documents = Array.from({ length: MAX_RESOURCE_DOCUMENTS }, (_, i) => ({ key: String(i), dirty: true } as ResourceDocument));
    expect(updateDocument(documents, { key: 'extra' } as ResourceDocument)).toBe(documents);
    expect(updateDocument(documents, { ...documents[0], dirty: false })[0].dirty).toBe(false);
  });
});
