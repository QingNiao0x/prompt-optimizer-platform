import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/** Checks only behavior specified in the sealed original task. */
export function runContractChecks(mergeShoppingItems) {
  assert.equal(typeof mergeShoppingItems, 'function', 'Missing named function export');
  const results = [];
  const check = (id, run) => {
    try {
      run();
      results.push({ id, passed: true });
    } catch (error) {
      results.push({ id, passed: false, error: String(error?.message ?? error) });
    }
  };

  check('normal_merge', () => {
    assert.deepEqual(mergeShoppingItems([
      { name: ' Milk ', quantity: 2 },
      { name: 'milk', quantity: 3 },
      { name: 'Egg', quantity: 1 },
    ]), [{ name: 'Milk', quantity: 5 }, { name: 'Egg', quantity: 1 }]);
  });

  check('key_and_display_name', () => {
    assert.deepEqual(mergeShoppingItems([
      { name: ' A  B ', quantity: 1 },
      { name: 'a b', quantity: 3 },
      { name: 'a  b', quantity: 2 },
    ]), [{ name: 'A  B', quantity: 3 }, { name: 'a b', quantity: 3 }]);
  });

  check('first_seen_order', () => {
    assert.deepEqual(mergeShoppingItems([
      { name: 'Beta', quantity: 1 },
      { name: 'alpha', quantity: 2 },
      { name: 'BETA', quantity: 4 },
    ]), [{ name: 'Beta', quantity: 5 }, { name: 'alpha', quantity: 2 }]);
  });

  check('empty_fresh_array', () => {
    const input = [];
    const result = mergeShoppingItems(input);
    assert.deepEqual(result, []);
    assert.notEqual(result, input);
  });

  check('invalid_parameter', () => {
    for (const input of [undefined, null, {}, 'milk', 1, true]) {
      assert.throws(() => mergeShoppingItems(input), TypeError);
    }
  });

  check('invalid_item_and_name', () => {
    const invalid = [null, [], 'milk', {}, { name: 1, quantity: 1 },
      { name: new String('milk'), quantity: 1 }, { name: '', quantity: 1 },
      { name: ' \t ', quantity: 1 }];
    for (const item of invalid) {
      assert.throws(() => mergeShoppingItems([item]), TypeError);
    }
  });

  check('invalid_quantity', () => {
    for (const quantity of [undefined, null, 0, -1, 1.5, NaN, Infinity,
      '2', true, Number.MAX_SAFE_INTEGER + 1]) {
      assert.throws(() => mergeShoppingItems([{ name: 'Milk', quantity }]), TypeError);
    }
  });

  check('safe_integer_and_overflow', () => {
    assert.deepEqual(mergeShoppingItems([
      { name: 'Milk', quantity: Number.MAX_SAFE_INTEGER - 1 },
      { name: 'MILK', quantity: 1 },
    ]), [{ name: 'Milk', quantity: Number.MAX_SAFE_INTEGER }]);
    assert.throws(() => mergeShoppingItems([
      { name: 'Milk', quantity: Number.MAX_SAFE_INTEGER },
      { name: 'MILK', quantity: 1 },
    ]), RangeError);
  });

  check('immutability_and_shape', () => {
    const first = Object.freeze({ name: ' Milk ', quantity: 2, note: 'ignore' });
    const second = Object.freeze({ name: 'MILK', quantity: 1 });
    const input = Object.freeze([first, second]);
    const result = mergeShoppingItems(input);
    assert.deepEqual(result, [{ name: 'Milk', quantity: 3 }]);
    assert.notEqual(result, input);
    assert.notEqual(result[0], first);
    assert.notEqual(result[0], second);
    assert.equal(first.name, ' Milk ');
    assert.equal(first.quantity, 2);
  });

  check('valid_object_and_string_keys', () => {
    const nullPrototypeItem = Object.assign(Object.create(null), { name: ' Bread ', quantity: 2 });
    assert.deepEqual(mergeShoppingItems([
      { name: '__proto__', quantity: 1 },
      { name: 'constructor', quantity: 3 },
      { name: '__PROTO__', quantity: 4 },
      nullPrototypeItem,
    ]), [{ name: '__proto__', quantity: 5 }, { name: 'constructor', quantity: 3 },
      { name: 'Bread', quantity: 2 }]);
  });

  return { passed: results.every((result) => result.passed), results };
}

const directlyInvoked = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (directlyInvoked) {
  if (!process.argv[2]) {
    console.error('Usage: node H04.check.mjs <absolute path to extracted artifact.mjs>');
    process.exitCode = 2;
  } else {
    try {
      const artifact = await import(pathToFileURL(path.resolve(process.argv[2])).href);
      const result = runContractChecks(artifact.mergeShoppingItems);
      console.log(JSON.stringify(result));
      process.exitCode = result.passed ? 0 : 1;
    } catch (error) {
      console.log(JSON.stringify({ passed: false, artifactLoadFailed: true,
        error: String(error?.message ?? error) }));
      process.exitCode = 1;
    }
  }
}
