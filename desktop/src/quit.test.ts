import { EventEmitter } from 'node:events';
import { describe, expect, it, vi } from 'vitest';
import { quitAfterWindowConsent, quitWindowGate, type QuitWindow } from './quit.js';

function windowFixture(blocked: boolean) {
  const events = new EventEmitter();
  const preventDefault = vi.fn();
  const window: QuitWindow = {
    onClosed(listener) {
      events.once('closed', listener);
      return () => { events.removeListener('closed', listener); };
    },
    onPreventUnload(listener) {
      events.on('blocked', listener);
      return () => { events.removeListener('blocked', listener); };
    },
    close() {
      if (blocked) events.emit('blocked', { preventDefault });
      else events.emit('closed');
    }
  };
  return { window, events, preventDefault };
}

describe('desktop quit consent', () => {
  it('cleans listeners without reading the destroyed BrowserWindow webContents getter', async () => {
    const events = new EventEmitter();
    const contents = new EventEmitter();
    let destroyed = false;
    const source = {
      once: (event: 'closed', listener: () => void) => events.once(event, listener),
      removeListener: (event: 'closed', listener: () => void) => events.removeListener(event, listener),
      get webContents() {
        if (destroyed) throw new Error('Object has been destroyed');
        return contents;
      },
      close() {
        const event = { preventDefault: vi.fn() };
        contents.emit('will-prevent-unload', event);
        expect(event.preventDefault).toHaveBeenCalledOnce();
        destroyed = true;
        events.emit('closed');
      }
    };
    const stop = vi.fn(async () => {});
    const quit = vi.fn();
    expect(await quitAfterWindowConsent(quitWindowGate(source), () => true, stop, quit)).toBe(true);
    expect(stop).toHaveBeenCalledOnce();
    expect(quit).toHaveBeenCalledOnce();
    expect(contents.eventNames()).toEqual([]);
  });
  it('keeps the backend alive when the user continues, and allows another attempt', async () => {
    const fixture = windowFixture(true);
    const stop = vi.fn(async () => {});
    const quit = vi.fn();
    expect(await quitAfterWindowConsent(fixture.window, () => false, stop, quit)).toBe(false);
    expect(stop).not.toHaveBeenCalled();
    expect(quit).not.toHaveBeenCalled();
    expect(fixture.preventDefault).not.toHaveBeenCalled();
    expect(fixture.events.eventNames()).toEqual([]);

    const second = quitAfterWindowConsent(fixture.window, () => true, stop, quit);
    expect(fixture.preventDefault).toHaveBeenCalledOnce();
    expect(stop).not.toHaveBeenCalled();
    fixture.events.emit('closed');
    expect(await second).toBe(true);
    expect(stop).toHaveBeenCalledOnce();
    expect(quit).toHaveBeenCalledOnce();
    expect(fixture.events.eventNames()).toEqual([]);
  });

  it('waits for the actual close and backend shutdown before exiting', async () => {
    const fixture = windowFixture(true);
    let finishStop!: () => void;
    const stop = vi.fn(() => new Promise<void>((resolve) => { finishStop = resolve; }));
    const quit = vi.fn();
    const result = quitAfterWindowConsent(fixture.window, () => true, stop, quit);
    expect(stop).not.toHaveBeenCalled();
    fixture.events.emit('closed');
    await Promise.resolve();
    expect(stop).toHaveBeenCalledOnce();
    expect(quit).not.toHaveBeenCalled();
    finishStop();
    expect(await result).toBe(true);
    expect(quit).toHaveBeenCalledOnce();
  });

  it('quits normally without unsaved work or a window', async () => {
    for (const window of [windowFixture(false).window, undefined]) {
      const confirm = vi.fn(() => false);
      const stop = vi.fn(async () => {});
      const quit = vi.fn();
      expect(await quitAfterWindowConsent(window, confirm, stop, quit)).toBe(true);
      expect(confirm).not.toHaveBeenCalled();
      expect(stop).toHaveBeenCalledOnce();
      expect(quit).toHaveBeenCalledOnce();
    }
  });
});
