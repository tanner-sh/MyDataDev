import { describe, expect, it } from 'vitest';
import { MAX_RESOURCE_DOCUMENTS, rekeyDocument, resourceDocumentKey, updateDocument, type ResourceDocument } from './resourceWorkspaces';
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
  it('re-keys a provisional tab to the canonical name in place', () => {
    const typed = resourceDocumentKey(1, 'object', 'S', 'sim_assetsprop');
    const canonical = resourceDocumentKey(1, 'object', 'S', 'SIM_ASSETSPROP');
    const documents = [{ key: 'a' }, { key: typed }, { key: 'b' }] as ResourceDocument[];
    expect(rekeyDocument(documents, typed, { key: canonical } as ResourceDocument).map(item => item.key)).toEqual(['a', canonical, 'b']);
  });
  it('merges into the canonical tab when it is already open', () => {
    const documents = [{ key: 'canon', dirty: true }, { key: 'typed', dirty: false }] as ResourceDocument[];
    const next = rekeyDocument(documents, 'typed', { key: 'canon', dirty: false } as ResourceDocument);
    expect(next.map(item => item.key)).toEqual(['canon']);
    expect(next[0].dirty).toBe(false);
  });
});
