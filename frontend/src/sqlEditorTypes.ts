/**
 * SQL 编辑器的抽象接口。
 *
 * <p>以前 App.tsx 直接引 monaco-editor 的类型，业务代码和某一个编辑器实现长在了一起：
 * 补全项要按 Monaco 的形状构造，光标位置要在「行列」和「偏移量」之间来回换算，鼠标事件
 * 也是 Monaco 的。换编辑器时这些全都要改。</p>
 *
 * <p>这里只声明工作台真正需要的那几件事，全部以字符偏移量表达 —— 上层的 SQL 分析
 * （analyzeSqlCompletion、findSqlObjectReferenceAtOffset）本来就是按偏移量工作的，
 * 行列坐标只是中间商。</p>
 */

/** 文档中的一段字符区间，端点是从 0 开始的偏移量。 */
export type SqlRange = { start: number; end: number };

export type SqlCompletionKind = 'table' | 'column' | 'schema' | 'keyword';

export type SqlCompletionItem = {
  label: string;
  kind: SqlCompletionKind;
  insertText: string;
  detail?: string;
  /** 排序键，小的在前。跨来源统一排序时用。 */
  sortText?: string;
};

export type SqlCompletionRequest = {
  text: string;
  offset: number;
  /** 是否由用户通过 Ctrl/Cmd+Space 显式请求。 */
  explicit: boolean;
  /** 由触发字符引起时带上它，否则为空。 */
  triggerCharacter?: string;
  /** 编辑器放弃这次补全（用户继续输入、关闭弹窗）时触发。 */
  signal: AbortSignal;
};

export type SqlCompletionResult = {
  /** 被替换掉的区间；补全项插入到这里。 */
  range: SqlRange;
  items: SqlCompletionItem[];
  /**
   * 结果还不完整，用户继续输入时要重新问一次。
   * 为 false 时编辑器可以在本地按前缀过滤已有结果，不再回调。
   */
  incomplete: boolean;
};

/** 编辑器实例暴露给工作台的操作面。 */
export type SqlEditorHandle = {
  getValue(): string;
  setValue(value: string): void;
  focus(): void;
  getSelection(): SqlRange;
  setSelection(range: SqlRange): void;
  /**
   * 替换一段文本。
   *
   * <p>格式化必须走这个而不是 setValue：整篇重写会把撤销历史一起清掉，用户按下 Ctrl+Z
   * 想撤销格式化时会发现之前的编辑也没了。</p>
   */
  replaceRange(range: SqlRange, text: string): void;
};

/**
 * 挂载回调。返回值会在编辑器销毁时调用，用来解绑上层自己注册的东西。
 */
export type SqlEditorOnMount = (handle: SqlEditorHandle) => void | (() => void);

export type SqlEditorProps = {
  value: string;
  themeMode: 'light' | 'dark';
  readOnly?: boolean;
  height?: string | number;
  onChange: (value: string) => void;
  onMount?: SqlEditorOnMount;
  /** Ctrl/Cmd+Enter。 */
  onExecute?: () => void;
  /** Ctrl/Cmd+Shift+F。 */
  onFormat?: () => void;
  completionSource?: (request: SqlCompletionRequest) => Promise<SqlCompletionResult | null>;
  /**
   * 按住 Ctrl/Cmd 悬停时问上层「这个位置有没有可跳转的对象」。
   *
   * 返回要高亮的区间，没有就返回 null。装饰由编辑器负责画，上层只回答语义问题 ——
   * 这样「哪里算一个对象引用」的判断留在业务侧，而画线、清线、跟随修饰键的琐事不外泄。
   */
  onDefinitionProbe?: (offset: number | null) => SqlRange | null;
  /** 按住 Ctrl/Cmd 点击一个可跳转位置。 */
  onDefinitionActivate?: (offset: number) => void;
  /**
   * 问上层「这段 SQL 里有哪些表名在当前 Schema 里找不到」。
   *
   * 与定义跳转同一套分工：语义判断（哪些名字算未知）留在业务侧，画波浪线、跟随文档改动、
   * 节流这些琐事在编辑器里。上层拿不准时返回空数组即可 —— 不提示永远好过误报。
   */
  onResolveUnknownObjects?: (sql: string) => SqlRange[];
};
