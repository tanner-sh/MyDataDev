import path from 'node:path';

export type DesktopEnvironment = Record<string, string | undefined>;

export type DesktopPaths = {
  home: string;
  data: string;
  backups: string;
  /** 定时导出的产物目录。 */
  exports: string;
  sqlFiles: string;
  logs: string;
  secretFile: string;
};

export function defaultDesktopHome(platform: NodeJS.Platform, environment: DesktopEnvironment, userHome: string) {
  const platformPath = platform === 'win32' ? path.win32 : path.posix;
  if (platform === 'darwin') return platformPath.join(userHome, 'Library', 'Application Support', 'MyDataDev');
  if (platform === 'win32') {
    return platformPath.join(environment.LOCALAPPDATA || platformPath.join(userHome, 'AppData', 'Local'), 'MyDataDev');
  }
  return platformPath.join(environment.XDG_DATA_HOME || platformPath.join(userHome, '.local', 'share'), 'MyDataDev');
}

export function resolveDesktopPaths(platform: NodeJS.Platform, environment: DesktopEnvironment, userHome: string): DesktopPaths {
  const platformPath = platform === 'win32' ? path.win32 : path.posix;
  const home = platformPath.resolve(environment.MYDATADEV_DESKTOP_HOME || defaultDesktopHome(platform, environment, userHome));
  return {
    home,
    data: platformPath.join(home, 'data'),
    backups: platformPath.join(home, 'backups'),
    exports: platformPath.join(home, 'exports'),
    sqlFiles: platformPath.join(home, 'sql-files'),
    logs: platformPath.join(home, 'logs'),
    secretFile: platformPath.join(home, 'desktop-secret.json')
  };
}

export function bundledBackendPaths(resourcesPath: string, platform: NodeJS.Platform) {
  const platformPath = platform === 'win32' ? path.win32 : path.posix;
  return {
    java: platformPath.join(resourcesPath, 'runtime', 'bin', platform === 'win32' ? 'java.exe' : 'java'),
    jar: platformPath.join(resourcesPath, 'backend', 'mydatadev-backend.jar')
  };
}
