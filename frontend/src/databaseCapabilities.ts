import type { Connection } from './types';
export type CapabilityFeature = { key: string; label: string; status: 'AVAILABLE' | 'UNAVAILABLE' | 'UNVERIFIED'; reason: string };
export type ServerInfo = { product: string; version: string; driver: string; tenantId?: string; tenantName?: string; compatibilityMode?: string; typeMatches?: boolean; warnings: string[] };
export type CapabilityReport = { server: ServerInfo; features: CapabilityFeature[] };
export function applyCapabilityReport(connection: Connection, report: CapabilityReport): Connection {
  const capabilities = { ...connection.capabilities };
  for (const key of ['tableBrowse', 'tableEdit', 'tableDesign', 'explain'] as const)
    if (report.features.some(feature => feature.key === key && feature.status === 'UNAVAILABLE')) capabilities[key] = false;
  return { ...connection, capabilities };
}

// A refreshed connection is a new snapshot: never reuse a probe of its old credentials or permissions.
export type ConnectionCapabilityReport = { source: Connection; report: CapabilityReport };
export function resolveLiveCapabilities(connection: Connection, cached?: ConnectionCapabilityReport): Connection {
  return cached?.source === connection ? applyCapabilityReport(connection, cached.report) : connection;
}
