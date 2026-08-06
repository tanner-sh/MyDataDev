import { parseImportFile } from '../importers';

interface ImportRequest {
  text: string;
  filename: string;
  tableName: string;
  columns: string[];
}

interface ImportResponse {
  success: boolean;
  result?: any;
  error?: string;
}

self.onmessage = (event: MessageEvent<ImportRequest>) => {
  try {
    const result = parseImportFile(
      event.data.text,
      event.data.filename,
      event.data.tableName,
      event.data.columns
    );
    const response: ImportResponse = { success: true, result };
    self.postMessage(response);
  } catch (error) {
    const response: ImportResponse = {
      success: false,
      error: (error as Error).message
    };
    self.postMessage(response);
  }
};
