export const ExitCode = {
  OK: 0,
  EXECUTION_FAILED: 1,
  USAGE_ERROR: 2,
  AUTH_ERROR: 3,
  DIFF_DETECTED: 4,
  UNREACHABLE: 5,
  INCOMPATIBLE_VERSION: 6,
  INTERRUPTED: 130
} as const;

export type ExitCodeValue = (typeof ExitCode)[keyof typeof ExitCode];

export class CliError extends Error {
  readonly exitCode: ExitCodeValue;
  readonly code: string;
  readonly details?: Record<string, unknown>;

  constructor(
    exitCode: ExitCodeValue,
    code: string,
    message: string,
    details?: Record<string, unknown>,
    options?: { cause?: unknown }
  ) {
    super(message, options);
    this.name = 'CliError';
    this.exitCode = exitCode;
    this.code = code;
    this.details = details;
  }
}

export function isCliError(error: unknown): error is CliError {
  return error instanceof CliError;
}
