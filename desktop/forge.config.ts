import type { ForgeConfig } from '@electron-forge/shared-types';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = path.dirname(fileURLToPath(import.meta.url));
const ignoredEntry = (name: string) => {
  const escapedName = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`^/${escapedName}(?:/|$)`);
};
const icon = process.platform === 'darwin'
  ? path.join(directory, 'assets', 'icon.icns')
  : process.platform === 'win32'
    ? path.join(directory, 'assets', 'icon.ico')
    : path.join(directory, 'assets', 'icon.png');

const config: ForgeConfig = {
  packagerConfig: {
    name: 'MyDataDev',
    executableName: 'MyDataDev',
    appBundleId: 'com.tanner.mydatadev',
    appCategoryType: 'public.app-category.developer-tools',
    asar: true,
    prune: true,
    ignore: [
      ignoredEntry('assets'),
      ignoredEntry('resources'),
      ignoredEntry('scripts'),
      ignoredEntry('src'),
      ignoredEntry('node_modules/.vite'),
      ignoredEntry('release-assets'),
      ignoredEntry('forge.config.ts'),
      ignoredEntry('package-lock.json'),
      ignoredEntry('tsconfig.json')
    ],
    icon,
    extraResource: [
      path.join(directory, 'resources', 'backend'),
      path.join(directory, 'resources', 'runtime'),
      path.join(directory, 'assets', 'icon.png')
    ]
  },
  makers: [
    {
      name: '@electron-forge/maker-dmg',
      config: { name: 'MyDataDev', format: 'ULFO' }
    },
    {
      name: '@electron-forge/maker-squirrel',
      config: {
        name: 'MyDataDev',
        authors: 'Tanner',
        description: 'MyDataDev cross-platform database workbench',
        setupIcon: path.join(directory, 'assets', 'icon.ico')
      }
    },
    {
      name: '@electron-forge/maker-deb',
      config: {
        options: {
          name: 'mydatadev',
          productName: 'MyDataDev',
          genericName: 'Database Workbench',
          bin: 'MyDataDev',
          categories: ['Development', 'Database'],
          icon: path.join(directory, 'assets', 'icon.png')
        }
      }
    },
    {
      name: '@electron-forge/maker-rpm',
      config: {
        options: {
          name: 'mydatadev',
          productName: 'MyDataDev',
          genericName: 'Database Workbench',
          bin: 'MyDataDev',
          categories: ['Development', 'Database'],
          icon: path.join(directory, 'assets', 'icon.png')
        }
      }
    }
  ]
};

export default config;
