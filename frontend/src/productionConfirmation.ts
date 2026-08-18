export function normalizeProductionConfirmation(value: string) {
  return value.trim();
}

export function matchesProductionConnectionName(value: string, expected: string) {
  return normalizeProductionConfirmation(value) === expected;
}
