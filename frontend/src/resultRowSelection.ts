export type ResultRowSelectionInput = {
  current: string[];
  clicked: string;
  displayed: string[];
  anchor?: string;
  toggle?: boolean;
  range?: boolean;
};

export type ResultRowSelection = { selected: string[]; anchor: string };

export function updateResultRowSelection(input: ResultRowSelectionInput): ResultRowSelection {
  const current = new Set(input.current);
  if (input.range && input.anchor) {
    const anchorIndex = input.displayed.indexOf(input.anchor);
    const clickedIndex = input.displayed.indexOf(input.clicked);
    if (anchorIndex >= 0 && clickedIndex >= 0) {
      const range = input.displayed.slice(Math.min(anchorIndex, clickedIndex), Math.max(anchorIndex, clickedIndex) + 1);
      if (!input.toggle) current.clear();
      range.forEach((key) => current.add(key));
      return { selected: input.displayed.filter((key) => current.has(key)), anchor: input.anchor };
    }
  }
  if (input.toggle) {
    if (current.has(input.clicked)) current.delete(input.clicked);
    else current.add(input.clicked);
    return { selected: input.displayed.filter((key) => current.has(key)), anchor: input.clicked };
  }
  return { selected: [input.clicked], anchor: input.clicked };
}
