import { describe, expect, it, vi } from 'vitest';
import { createObjectRowActivation, OBJECT_ROW_SINGLE_CLICK_DELAY_MS } from './objectRowActivation';

function harness() {
  const timers = new Map<number, () => void>();
  let nextId = 0;
  const openDetail = vi.fn();
  const openData = vi.fn();
  const activation = createObjectRowActivation(
    { openDetail, openData },
    {
      setTimer: (callback, delayMs) => {
        expect(delayMs).toBe(OBJECT_ROW_SINGLE_CLICK_DELAY_MS);
        nextId += 1;
        timers.set(nextId, callback);
        return nextId;
      },
      clearTimer: (id) => timers.delete(id)
    }
  );
  return {
    activation,
    openDetail,
    openData,
    pending: () => timers.size,
    flush: () => [...timers.values()].forEach((callback) => callback())
  };
}

describe('createObjectRowActivation', () => {
  it('opens the structure a moment after a lone click', () => {
    const { activation, openDetail, flush } = harness();

    activation.click('customers', true);
    expect(openDetail).not.toHaveBeenCalled();

    flush();
    expect(openDetail).toHaveBeenCalledWith('customers');
  });

  it('opens the data on a double click and never flashes the structure first', () => {
    const { activation, openDetail, openData, flush } = harness();

    activation.click('customers', true);
    activation.doubleClick('customers', true);
    flush();

    expect(openData).toHaveBeenCalledWith('customers');
    expect(openDetail).not.toHaveBeenCalled();
  });

  it('does not delay objects that have no data view', () => {
    const { activation, openDetail, openData, pending } = harness();

    activation.click('vip_customers', false);

    expect(openDetail).toHaveBeenCalledWith('vip_customers');
    expect(pending()).toBe(0);
    activation.doubleClick('vip_customers', false);
    expect(openData).not.toHaveBeenCalled();
  });

  it('drops a superseded click when another row is clicked first', () => {
    const { activation, openDetail, flush } = harness();

    activation.click('customers', true);
    activation.click('orders', true);
    flush();

    expect(openDetail).toHaveBeenCalledTimes(1);
    expect(openDetail).toHaveBeenCalledWith('orders');
  });

  it('cancels a pending click on dispose', () => {
    const { activation, openDetail, flush, pending } = harness();

    activation.click('customers', true);
    activation.dispose();
    flush();

    expect(pending()).toBe(0);
    expect(openDetail).not.toHaveBeenCalled();
  });
});
