我有个不带界面的 Node.js 小工具，附件里是现有 package.json 和 src/mergeShoppingItems.js。请补完里面的命名导出函数 mergeShoppingItems(items)，用来合并购物清单，保持 ES module 和现有函数签名。

要求已经定好了：

- 参数必须是数组；每项必须是非 null、非数组的对象，name 必须是原始字符串且 trim 后不为空，quantity 必须是大于 0 的安全整数。任一项不符合就抛 TypeError，额外字段忽略。
- 用 name.trim().toLowerCase() 作为合并键，不做其他名称替换。相同键的 quantity 相加；返回顺序按键首次出现的顺序，返回对象只有 name 和 quantity，name 用第一次出现时 trim 后的原始大小写。
- 如果某一项合并后的 quantity 超出安全整数范围，抛 RangeError。
- 空数组返回新的空数组；所有情况下都不修改传入数组或其中的对象，返回项也是新对象。
- 不加依赖，不读写文件，不使用网络，不新增界面。

例如 [{name:' Milk ',quantity:2},{name:'milk',quantity:3},{name:'Egg',quantity:1}] 应得到 [{name:'Milk',quantity:5},{name:'Egg',quantity:1}]。

只输出 src/mergeShoppingItems.js 的完整替换内容，放在一个 javascript 代码块里，不需要额外说明或其他文件。
