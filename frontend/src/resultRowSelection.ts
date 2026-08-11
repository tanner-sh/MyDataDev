export type ResultRowSelectionInput = {
  current: string[];
  clicked: string;
  displayed: string[];
  displayedIndex?: ReadonlyMap<string, number>;
  anchor?: string;
  toggle?: boolean;
  range?: boolean;
};

export type ResultRowSelection = { selected: string[]; anchor: string };

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
