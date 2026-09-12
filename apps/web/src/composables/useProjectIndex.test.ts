import { afterEach, describe, expect, it, vi } from 'vitest';

import { clearPersistedProjectSelection } from './useProjectIndex';

describe('clearPersistedProjectSelection', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('should remove the legacy project selection used to restore the upload panel', () => {
    const removeItem = vi.fn();
    vi.stubGlobal('window', {
      localStorage: { removeItem },
    });

    clearPersistedProjectSelection();

    expect(removeItem).toHaveBeenCalledOnce();
    expect(removeItem).toHaveBeenCalledWith('prompt-optimizer.current-project-index.v1');
  });

  it('should keep page initialization available when browser storage is disabled', () => {
    vi.stubGlobal('window', {
      localStorage: {
        removeItem: () => {
          throw new DOMException('storage disabled', 'SecurityError');
        },
      },
    });

    expect(() => clearPersistedProjectSelection()).not.toThrow();
  });
});
