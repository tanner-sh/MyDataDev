export type ResultRowSelectionInput = {
  current: string[];
  clicked: string;
  displayed: string[];
  displayedIndex?: ReadonlyMap<string, number>;
  anchor?: string;
  toggle?: boolean;
  range?: boolean;
};

export type ResultRowSelection = { selected: string[]; anchor?: string };

export type ResultGridKeyboardAction = 'select-all' | 'copy' | 'clear-selection';

export function resolveResultGridKeyboardAction(input: {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  textEntry?: boolean;
}): ResultGridKeyboardAction | undefined {
  if (input.textEntry) return undefined;
  const key = input.key.toLocaleLowerCase();
  const modifier = input.ctrlKey || input.metaKey;
  if (modifier && key === 'a') return 'select-all';
  if (modifier && key === 'c') return 'copy';
  if (key === 'escape') return 'clear-selection';
  return undefined;
}

export function replaceResultRowSelection(
  displayed: string[],
  requested: Iterable<string>,
  anchor?: string
): ResultRowSelection {
  const requestedSet = new Set(requested);
  const selected = displayed.filter((key) => requestedSet.has(key));
  const displayedSet = new Set(displayed);
  return {
    selected,
    anchor: anchor && displayedSet.has(anchor) ? anchor : selected[0]
  };
}

export function updateResultRowSelection(input: ResultRowSelectionInput): ResultRowSelection {
  const displayedIndex = input.displayedIndex || new Map(input.displayed.map((key, index) => [key, index]));
  if (input.range && input.anchor) {
    const anchorIndex = displayedIndex.get(input.anchor);
    const clickedIndex = displayedIndex.get(input.clicked);
    if (anchorIndex !== undefined && clickedIndex !== undefined) {
      const range = input.displayed.slice(Math.min(anchorIndex, clickedIndex), Math.max(anchorIndex, clickedIndex) + 1);
      if (!input.toggle) return { selected: range, anchor: input.anchor };
      const current = new Set(input.current);
      range.forEach((key) => current.add(key));
      return { selected: orderSelectedKeys(current, displayedIndex), anchor: input.anchor };
    }
  }
  if (input.toggle) {
    const current = new Set(input.current);
    if (current.has(input.clicked)) {
      return { selected: input.current.filter((key) => key !== input.clicked), anchor: input.clicked };
    }
    current.add(input.clicked);
    return { selected: orderSelectedKeys(current, displayedIndex), anchor: input.clicked };
  }
  return { selected: [input.clicked], anchor: input.clicked };
}

function orderSelectedKeys(keys: ReadonlySet<string>, displayedIndex: ReadonlyMap<string, number>): string[] {
  return [...keys]
    .filter((key) => displayedIndex.has(key))
    .sort((left, right) => displayedIndex.get(left)! - displayedIndex.get(right)!);
}
