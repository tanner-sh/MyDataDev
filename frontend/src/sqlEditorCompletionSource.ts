import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete';
import { completionTriggerCharacter, toEditorCompletion } from './sqlEditorCompletion';
import type { SqlEditorProps } from './sqlEditorTypes';

export type CompletionLoadStatus = { loading?: boolean; error?: string } | undefined;

/** 只允许当前请求更新状态；编辑器切换文档和卸载时也能主动使旧请求失效。 */
export function createSqlEditorCompletionSource(getSource: () => SqlEditorProps['completionSource'], onStatus: (status: CompletionLoadStatus) => void) {
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  function reset() {
    generation++;
    clearTimeout(timer);
    onStatus(undefined);
  }
  async function source(context: CompletionContext): Promise<CompletionResult | null> {
    reset();
    const provider = getSource();
    if (!provider) return null;
    const currentGeneration = generation;
    const controller = new AbortController();
    const current = () => generation === currentGeneration && !context.aborted && !controller.signal.aborted;
    context.addEventListener('abort', () => {
      controller.abort();
      if (generation === currentGeneration) reset();
    }, { onDocChange: true });
    timer = setTimeout(() => { if (current()) onStatus({ loading: true }); }, 200);
    try {
      const text = context.state.doc.toString();
      const result = await provider({ text, offset: context.pos, explicit: context.explicit,
        triggerCharacter: completionTriggerCharacter(text, context.pos, context.explicit), signal: controller.signal });
      if (!current()) return null;
      onStatus(result?.warning ? { error: result.warning } : undefined);
      return result?.items.length ? toEditorCompletion(result) : null;
    } catch {
      if (current()) onStatus({ error: '补全加载失败' });
      return null;
    } finally {
      if (generation === currentGeneration) clearTimeout(timer);
    }
  }
  return { source, reset };
}
