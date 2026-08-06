export function buildRestoreUploadPath(filename: string, fileFormat: string, sourceDbType: string): string {
  const params = new URLSearchParams({ filename, fileFormat, sourceDbType });
  return `/restores/uploads?${params.toString()}`;
}
