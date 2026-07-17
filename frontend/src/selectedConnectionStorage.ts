import type { Connection } from './types';

export const SELECTED_CONNECTION_STORAGE_KEY = 'mydatadev.selectedConnectionId';

type ConnectionStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export function readSelectedConnectionId(storage: ConnectionStorage = window.localStorage): number | undefined {
  try {
    const value = storage.getItem(SELECTED_CONNECTION_STORAGE_KEY);
    if (value == null) return undefined;
    const id = Number(value);
    return Number.isSafeInteger(id) && id > 0 ? id : undefined;
  } catch {
    return undefined;
  }
}

export function writeSelectedConnectionId(connectionId: number | null | undefined, storage: ConnectionStorage = window.localStorage): void {
  try {
    if (connectionId == null) storage.removeItem(SELECTED_CONNECTION_STORAGE_KEY);
    else storage.setItem(SELECTED_CONNECTION_STORAGE_KEY, String(connectionId));
  } catch {
    // Connection selection must keep working when browser storage is unavailable.
  }
}

export function resolveSelectedConnection(
  connections: Connection[],
  candidates: Array<number | null | undefined>
): Connection | null {
  for (const candidate of candidates) {
    if (!candidate) continue;
    const match = connections.find((connection) => connection.id === candidate);
    if (match) return match;
  }
  return connections[0] || null;
}
