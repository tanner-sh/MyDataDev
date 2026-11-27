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
const adHocMacSigning = process.platform === 'darwin'
  ? {
      identity: '-',
      identityValidation: false,
      continueOnError: false,
      strictVerify: true,
      preAutoEntitlements: false,
      preEmbedProvisioningProfile: false,
      optionsForFile: () => ({
        // Ad-hoc signatures have no Team ID, so Electron's libraries cannot use
        // hardened library validation. This still seals the complete app bundle.
        hardenedRuntime: false,
        timestamp: 'none'
      })
    }
  : undefined;

const config: ForgeConfig = {
  packagerConfig: {
    name: 'MyDataDev',
    executableName: 'MyDataDev',
    appBundleId: 'com.tanner.mydatadev',
    appCategoryType: 'public.app-category.developer-tools',
    osxSign: adHocMacSigning,
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
    // 注意：内置 CLI（resources/cli/bin/mdd）通过 ELECTRON_RUN_AS_NODE=1 复用 Electron 自带的 Node 运行时。
    // 当前未关闭 RunAsNode fuse；若未来出于安全加固关闭该 fuse，必须把 resources/cli 改为独立打包的 SEA 二进制。
    extraResource: [
      path.join(directory, 'resources', 'backend'),
      path.join(directory, 'resources', 'runtime'),
      path.join(directory, 'resources', 'cli'),
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
          license: 'Apache-2.0',
          categories: ['Development', 'Database'],
          icon: path.join(directory, 'assets', 'icon.png')
        }
      }
    }
  ]
};

export default config;
