import type { Key } from 'react';
import type { DbObject } from './types';
import { objectTypeLabel } from './utils';

export type ObjectGroupModel = {
  key: string;
  label: string;
  objects: DbObject[];
};

export function databaseObjectGroup(type: string) {
  const normalized = type.toUpperCase();
  if (normalized.includes('VIEW')) return { key: 'VIEW', label: '视图' };
  if (normalized.includes('TABLE')) return { key: 'TABLE', label: '表' };
  return { key: normalized || 'OTHER', label: objectTypeLabel(type) || '其他对象' };
}

export function groupDatabaseObjects(objects: DbObject[]): ObjectGroupModel[] {
  const groups = new Map<string, ObjectGroupModel>();
  objects.forEach((object) => {
    const group = databaseObjectGroup(object.type);
    const current = groups.get(group.key) || { ...group, objects: [] };
    current.objects.push(object);
    groups.set(group.key, current);
  });
  const order = (key: string) => key === 'TABLE' ? 0 : key === 'VIEW' ? 1 : 2;
  return [...groups.values()]
    .map((group) => ({
      ...group,
      objects: [...group.objects].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN', { numeric: true, sensitivity: 'base' }))
    }))
    .sort((left, right) => order(left.key) - order(right.key) || left.label.localeCompare(right.label, 'zh-CN'));
}

export function databaseObjectNodeKey(object: Pick<DbObject, 'schemaName' | 'name' | 'type'>) {
  const group = databaseObjectGroup(object.type);
  return treeNodeKey('object', object.schemaName || '', group.key, object.type, object.name);
}

export function objectStructureKey(object: Pick<DbObject, 'schemaName' | 'name'>) {
  return `${object.schemaName || ''}.${object.name}`;
}

export function withLoadedObjectStructure(object: DbObject, structure?: Pick<DbObject, 'columns' | 'indexes'>): DbObject {
  if (!structure) return object;
  return { ...object, columns: structure.columns, indexes: structure.indexes };
}

export function treeNodeKey(kind: string, ...parts: string[]) {
  return [kind, ...parts.map((part) => encodeURIComponent(part))].join(':');
}

export function sameDatabaseObject(left: Pick<DbObject, 'schemaName' | 'name'>, right: Pick<DbObject, 'schemaName' | 'name'>) {
  return (left.schemaName || '').toLocaleLowerCase() === (right.schemaName || '').toLocaleLowerCase()
    && left.name.toLocaleLowerCase() === right.name.toLocaleLowerCase();
}

export function findMatchingDatabaseObject(objects: DbObject[], target: Pick<DbObject, 'schemaName' | 'name'>) {
  const exact = objects.find((object) => (object.schemaName || '') === (target.schemaName || '') && object.name === target.name);
  if (exact) return exact;
  const folded = objects.filter((object) => sameDatabaseObject(object, target));
  return folded.length === 1 ? folded[0] : undefined;
}

export function keepOnlyObjectBranch(keys: Key[], objectKey: Key) {
  const target = String(objectKey);
  return keys.filter((key) => {
    const value = String(key);
    return !value.startsWith('object:') || value === target || value.startsWith(`${target}:`);
  });
}

export function collapseObjectBranch(keys: Key[], objectKey: Key) {
  const target = String(objectKey);
  return keys.filter((key) => {
    const value = String(key);
    return value !== target && !value.startsWith(`${target}:`);
  });
}
