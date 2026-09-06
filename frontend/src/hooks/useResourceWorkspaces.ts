import { useCallback, useRef, useState } from 'react';
import { MAX_RESOURCE_DOCUMENTS, updateDocument, type ResourceDocument, type TableSnapshot } from '../resourceWorkspaces';

export function useResourceWorkspaces() {
  const [documents, setDocuments] = useState<ResourceDocument[]>([]);
  const snapshots = useRef(new Map<string, TableSnapshot>());
  const ensure = (document: ResourceDocument) => {
    if (!documents.some(item => item.key === document.key) && documents.length >= MAX_RESOURCE_DOCUMENTS) return false;
    setDocuments(items => updateDocument(items, document));
    return true;
  };
  const markDirty = useCallback((key: string, dirty: boolean) => setDocuments(items => {
    if (!items.some(item => item.key === key && item.dirty !== dirty)) return items;
    return items.map(item => item.key === key ? { ...item, dirty } : item);
  }), []);
  const close = (key: string) => { snapshots.current.delete(key); setDocuments(items => items.filter(item => item.key !== key)); };
  return { documents, snapshots, ensure, markDirty, close };
}
