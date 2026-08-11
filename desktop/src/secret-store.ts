import { randomBytes } from 'node:crypto';
import { chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

export type SafeStorageAdapter = {
  available(): Promise<boolean>;
  encrypt(value: string): Promise<Buffer>;
  decrypt(value: Buffer): Promise<string>;
  backend(): string;
};

type StoredSecret = {
  version: 1;
  encryptedKey: string;
  storageBackend: string;
};

export type DesktopSecret = {
  cryptoKey: string;
  insecureLinuxFallback: boolean;
};

export async function loadOrCreateDesktopSecret(
  secretFile: string,
  storage: SafeStorageAdapter,
  createKey: () => string = () => randomBytes(32).toString('base64url')
): Promise<DesktopSecret> {
  if (!await storage.available()) throw new Error('操作系统安全存储当前不可用，无法启动 MyDataDev 桌面版。');

  let cryptoKey: string;
  try {
    const stored = JSON.parse(await readFile(secretFile, 'utf8')) as StoredSecret;
    if (stored.version !== 1 || !stored.encryptedKey) throw new Error('unsupported secret format');
    cryptoKey = await storage.decrypt(Buffer.from(stored.encryptedKey, 'base64'));
    if (!cryptoKey) throw new Error('empty decrypted secret');
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
      throw new Error('桌面加密密钥无法解密；为保护已保存的数据库密码，应用不会自动生成替代密钥。', { cause: error });
    }
    cryptoKey = createKey();
    const encrypted = await storage.encrypt(cryptoKey);
    const payload: StoredSecret = {
      version: 1,
      encryptedKey: encrypted.toString('base64'),
      storageBackend: storage.backend()
    };
    await mkdir(path.dirname(secretFile), { recursive: true });
    await writeFile(secretFile, `${JSON.stringify(payload, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    await chmod(secretFile, 0o600).catch(() => undefined);
  }

  return { cryptoKey, insecureLinuxFallback: storage.backend() === 'basic_text' };
}
