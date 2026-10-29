function __runEngineeringChecks() {
  const stringify = JSON.stringify.bind(JSON);
  const checks = [];
  const canonical = (value) => {
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
    return value;
  };
  const same = (actual, expected) => {
    if (stringify(canonical(actual)) !== stringify(canonical(expected))) throw new Error('VALUE_MISMATCH');
  };
  const truth = (condition) => { if (!condition) throw new Error('CONDITION_NOT_MET'); };
  const expectCode = (action, code) => {
    let caught;
    try { action(); } catch (error) { caught = error; }
    if (!caught || caught.code !== code) throw new Error('EXPECTED_BUSINESS_ERROR_NOT_RECEIVED');
  };
  const check = (id, action) => {
    try { action(); checks.push({ id, passed: true }); }
    catch (error) { checks.push({ id, passed: false, error: String(error?.message ?? error) }); }
  };
  const { createSeedSnapshot } = __projectLoad('src/data/seed.ts');
  const { createMemoryLoanRepository } = __projectLoad('src/data/memoryLoanRepository.ts');
  const { createLoanDesk } = __projectLoad('src/application/loanDeskService.ts');
  const { availabilityFor } = __projectLoad('src/domain/availability.ts');
  const fixture = () => {
    const seed = createSeedSnapshot();
    const repository = createMemoryLoanRepository(seed);
    return { seed, repository, desk: createLoanDesk(repository) };
  };
  const command = (quantity = 1) => ({ siteId: 'harbor', kitId: 'camera', quantity, purpose: '  合成用途  ' });
  const unchangedOnError = (fixture, action, code) => {
    const before = fixture.repository.snapshot();
    expectCode(action, code);
    same(fixture.repository.snapshot(), before);
  };

  check('B01', () => same(fixture().desk.quoteNew('harbor', 'camera'), { siteId: 'harbor', kitId: 'camera', total: 8, occupied: 3, available: 5 }));
  check('B02', () => same(fixture().desk.quoteNew('hill', 'camera').available, 1));
  check('B03', () => same(fixture().desk.quoteNew('harbor', 'tripod').available, 2));
  check('B04', () => {
    const state = createSeedSnapshot();
    const before = stringify(state);
    same(availabilityFor(state, { siteId: 'harbor', kitId: 'camera', excludeLoanId: 'L101' }).available, 7);
    same(stringify(state), before);
  });
  check('B05', () => same(fixture().desk.quoteEdit('L101').available, 7));
  check('B06', () => {
    const item = fixture();
    const before = item.repository.snapshot();
    const row = item.desk.updateLoan({ loanId: 'L101', quantity: 7, purpose: '  新用途  ' });
    same([row.quantity, row.purpose, row.revision, row.status], [7, '新用途', 2, 'CONFIRMED']);
    same(item.repository.snapshot().loans.filter((loan) => loan.id !== 'L101'), before.loans.filter((loan) => loan.id !== 'L101'));
    same(item.desk.quoteNew('harbor', 'camera').available, 0);
  });
  check('B07', () => {
    const item = fixture();
    const row = item.desk.createLoan(command(5));
    same([row.quantity, row.purpose, row.status, row.revision], [5, '合成用途', 'CONFIRMED', 1]);
    same(item.repository.snapshot().loans.length, 8);
    same(item.desk.quoteNew('harbor', 'camera').available, 0);
  });
  check('B08', () => {
    const item = fixture();
    unchangedOnError(item, () => item.desk.createLoan(command(6)), 'INSUFFICIENT_STOCK');
  });
  check('B09', () => {
    for (const quantity of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '2']) {
      const item = fixture();
      unchangedOnError(item, () => item.desk.createLoan(command(quantity)), 'INVALID_QUANTITY');
    }
  });
  check('B10', () => {
    for (const quantity of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
      const item = fixture();
      unchangedOnError(item, () => item.desk.updateLoan({ loanId: 'L101', quantity, purpose: '保留旧单' }), 'INVALID_QUANTITY');
    }
  });
  check('B11', () => {
    const item = fixture();
    same(item.desk.quoteNew('harbor', 'camera').available, 5);
    item.repository.insert(command(4));
    unchangedOnError(item, () => item.desk.createLoan(command(2)), 'INSUFFICIENT_STOCK');
  });
  check('B12', () => {
    const item = fixture();
    same(item.desk.quoteEdit('L101').available, 7);
    item.repository.insert(command(6));
    unchangedOnError(item, () => item.desk.updateLoan({ loanId: 'L101', quantity: 2, purpose: '不应保存' }), 'INSUFFICIENT_STOCK');
  });
  check('B13', () => {
    for (const loanId of ['L102', 'L103', 'L104', 'L105']) {
      const item = fixture();
      unchangedOnError(item, () => item.desk.updateLoan({ loanId, quantity: 1, purpose: '不能修改' }), 'READ_ONLY_LOAN');
    }
  });
  check('B14', () => {
    const item = fixture();
    unchangedOnError(item, () => item.desk.createLoan({ ...command(), kitId: 'speaker' }), 'UNKNOWN_STOCK');
    unchangedOnError(item, () => item.desk.updateLoan({ loanId: 'missing', quantity: 1, purpose: '' }), 'LOAN_NOT_FOUND');
  });
  check('B15', () => {
    const state = createSeedSnapshot();
    state.stocks.find((row) => row.siteId === 'harbor' && row.kitId === 'camera').total = 1;
    same(availabilityFor(state, { siteId: 'harbor', kitId: 'camera' }).available, 0);
  });

  const createNode = (kind, text = '') => ({ kind, text, props: {}, children: [], parent: null });
  const renderer = Vue.createRenderer({
    createElement: (tag) => ({ ...createNode('element'), tag }),
    createText: (text) => createNode('text', text),
    createComment: (text) => createNode('comment', text),
    setText: (node, text) => { node.text = text; },
    setElementText: (node, text) => { node.children = []; node.text = text; },
    patchProp: (node, key, oldValue, value) => { node.props[key] = value; },
    parentNode: (node) => node.parent,
    nextSibling: (node) => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    insert(node, parent, anchor = null) {
      if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1);
      node.parent = parent;
      const index = anchor ? parent.children.indexOf(anchor) : -1;
      if (index < 0) parent.children.push(node); else parent.children.splice(index, 0, node);
    },
    remove(node) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = null; },
  });
  const mount = (filename, props = {}) => {
    const root = createNode('root');
    const app = renderer.createApp(__projectLoad(filename).default, props);
    app.mount(root);
    return { root, app };
  };
  const find = (root, predicate) => {
    if (predicate(root)) return root;
    for (const child of root.children) { const result = find(child, predicate); if (result) return result; }
    return null;
  };
  const byTest = (root, id) => {
    const node = find(root, (item) => item.props['data-test'] === id);
    if (!node) throw new Error('EXPECTED_UI_ELEMENT_MISSING');
    return node;
  };
  const text = (node) => String(node.text ?? '') + node.children.map(text).join('');
  const send = (root, id, eventName, value) => {
    const node = byTest(root, id);
    if (node.props.disabled) throw new Error('DISABLED_CONTROL');
    const handler = node.props[eventName];
    if (typeof handler !== 'function') throw new Error('UI_HANDLER_MISSING');
    handler({ target: { value } });
    // Vue's pinned runtime renders the updated root synchronously here. No host
    // callback, timer, DOM shim, or artificial component implementation is used.
    root._vnode.component.update();
  };

  check('U01', () => {
    const item = fixture();
    const view = mount('src/views/NewLoanPage.vue', { desk: item.desk });
    same(text(byTest(view.root, 'available-count')), '5');
    view.app.unmount();
  });
  check('U02', () => {
    const item = fixture();
    const view = mount('src/views/EditLoanPage.vue', { desk: item.desk });
    same(text(byTest(view.root, 'available-count')), '7');
    view.app.unmount();
  });
  check('U03', () => {
    const item = fixture();
    const view = mount('src/views/NewLoanPage.vue', { desk: item.desk });
    send(view.root, 'quantity-input', 'onInput', '1');
    send(view.root, 'purpose-input', 'onInput', '  页面新用途  ');
    send(view.root, 'submit-loan', 'onClick');
    const row = item.repository.snapshot().loans.at(-1);
    same([row.quantity, row.purpose, row.status], [1, '页面新用途', 'CONFIRMED']);
    same(text(byTest(view.root, 'available-count')), '4');
    truth(text(byTest(view.root, 'success-message')).includes('已创建'));
    view.app.unmount();
  });
  check('U04', () => {
    const item = fixture();
    const before = item.repository.snapshot();
    const view = mount('src/views/NewLoanPage.vue', { desk: item.desk });
    send(view.root, 'quantity-input', 'onInput', '1.5');
    send(view.root, 'purpose-input', 'onInput', '草稿不可丢');
    send(view.root, 'submit-loan', 'onClick');
    same(item.repository.snapshot(), before);
    same(byTest(view.root, 'quantity-input').props.value, 1.5);
    same(byTest(view.root, 'purpose-input').props.value, '草稿不可丢');
    same(text(byTest(view.root, 'error-message')), '数量必须为正整数');
    view.app.unmount();
  });
  check('U05', () => {
    const item = fixture();
    const view = mount('src/views/EditLoanPage.vue', { desk: item.desk });
    send(view.root, 'quantity-input', 'onInput', '7');
    send(view.root, 'purpose-input', 'onInput', '  页面修改  ');
    send(view.root, 'submit-loan', 'onClick');
    const row = item.desk.getLoan('L101');
    same([row.quantity, row.purpose, row.revision], [7, '页面修改', 2]);
    same(text(byTest(view.root, 'available-count')), '7');
    truth(text(byTest(view.root, 'success-message')).includes('已保存'));
    view.app.unmount();
  });
  check('U06', () => {
    const item = fixture();
    const before = item.repository.snapshot();
    const view = mount('src/views/EditLoanPage.vue', { desk: item.desk });
    send(view.root, 'quantity-input', 'onInput', '8');
    send(view.root, 'purpose-input', 'onInput', '失败草稿');
    send(view.root, 'submit-loan', 'onClick');
    same(item.repository.snapshot(), before);
    same(byTest(view.root, 'quantity-input').props.value, 8);
    same(byTest(view.root, 'purpose-input').props.value, '失败草稿');
    same(text(byTest(view.root, 'error-message')), '可借数量不足，请按最新余量调整');
    view.app.unmount();
  });
  check('U07', () => {
    const view = mount('src/views/NewLoanPage.vue', { desk: fixture().desk });
    send(view.root, 'site-select', 'onChange', 'hill');
    same(text(byTest(view.root, 'available-count')), '1');
    send(view.root, 'site-select', 'onChange', 'harbor');
    send(view.root, 'kit-select', 'onChange', 'tripod');
    same(text(byTest(view.root, 'available-count')), '2');
    send(view.root, 'kit-select', 'onChange', 'speaker');
    truth(text(byTest(view.root, 'unknown-stock')).includes('未配置'));
    view.app.unmount();
  });
  check('U08', () => {
    const view = mount('src/views/EditLoanPage.vue', { desk: fixture().desk });
    send(view.root, 'loan-select', 'onChange', 'L104');
    same(byTest(view.root, 'quantity-input').props.disabled, true);
    same(byTest(view.root, 'submit-loan').props.disabled, true);
    truth(text(byTest(view.root, 'readonly-note')).includes('只读'));
    view.app.unmount();
  });
  check('U09', () => {
    const view = mount('src/App.vue');
    truth(byTest(view.root, 'new-page'));
    const navigate = (label) => {
      const button = find(view.root, (node) => node.tag === 'button' && text(node) === label);
      truth(button && typeof button.props.onClick === 'function');
      button.props.onClick();
      view.root._vnode.component.update();
    };
    navigate('修改借用'); truth(byTest(view.root, 'edit-page'));
    navigate('押金预览'); same(text(byTest(view.root, 'deposit-value')), '160.00 元');
    navigate('新建借用'); truth(byTest(view.root, 'new-page'));
    view.app.unmount();
  });
  check('U10', () => {
    const item = fixture();
    const view = mount('src/views/NewLoanPage.vue', { desk: item.desk });
    same(text(byTest(view.root, 'available-count')), '5');
    item.repository.insert(command(4));
    const before = item.repository.snapshot();
    send(view.root, 'quantity-input', 'onInput', '2');
    send(view.root, 'purpose-input', 'onInput', '稍后再调整');
    send(view.root, 'submit-loan', 'onClick');
    same(item.repository.snapshot(), before);
    same(byTest(view.root, 'quantity-input').props.value, 2);
    same(byTest(view.root, 'purpose-input').props.value, '稍后再调整');
    truth(text(byTest(view.root, 'error-message')).includes('最新余量'));
    send(view.root, 'refresh-new', 'onClick');
    same(text(byTest(view.root, 'available-count')), '1');
    view.app.unmount();
  });
  check('X01', () => {
    const legacy = __projectLoad('src/legacy/availability.ts');
    same(legacy.availabilityFor(5, 'a', [{ deviceCode: 'a', pieces: 2, closed: false }, { deviceCode: 'a', pieces: 1, closed: true }]), 3);
    const events = __projectLoad('src/events/eventSignupService.ts');
    same(events.previewSignup({ id: 'e', title: '合成活动', capacity: 5 }, 3, [
      { id: 's1', eventId: 'e', seats: 2, state: 'BOOKED' },
      { id: 's2', eventId: 'e', seats: 4, state: 'WAITING' },
    ]), { allowed: true, reason: '', remaining: 3 });
  });
  check('X02', () => {
    const view = mount('src/views/DepositPage.vue');
    same(text(byTest(view.root, 'deposit-value')), '160.00 元');
    view.app.unmount();
  });
  check('X03', () => {
    same(typeof process, 'undefined'); same(typeof Buffer, 'undefined');
    same(typeof fetch, 'undefined'); same(typeof setTimeout, 'undefined');
    let denied = false;
    try { ({}).constructor.constructor('return globalThis')(); } catch { denied = true; }
    truth(denied);
  });

  return stringify({ passed: checks.every((item) => item.passed), checks });
}
