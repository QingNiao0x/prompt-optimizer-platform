以下按你确认的要求交付：Python 快速排序函数，对整数列表升序排序，处理空列表和重复元素，并附输入与预期输出示例。

```python
def quick_sort(nums):
    """
    对整数列表进行升序快速排序。

    参数:
        nums: list[int]，待排序的整数列表。

    返回:
        list[int]，升序排序后的新列表。

    说明:
        - 空列表返回空列表。
        - 重复元素会被保留，并正确参与排序。
        - 不修改原列表，返回新列表。
    """
    # 基本情况：空列表或单元素列表已经有序
    if len(nums) <= 1:
        return nums[:]

    # 选择中间元素作为基准，避免在已排序输入上表现过差
    pivot = nums[len(nums) // 2]

    # 按基准值分成三部分：小于、等于、大于
    # 使用“等于”分区可以正确处理重复元素
    less = [x for x in nums if x < pivot]
    equal = [x for x in nums if x == pivot]
    greater = [x for x in nums if x > pivot]

    # 递归排序左右两部分，中间部分已等于基准值，无需再排序
    return quick_sort(less) + equal + quick_sort(greater)


if __name__ == "__main__":
    # 示例 1：普通整数列表
    input_list = [3, 6, 1, 8, 2, 6, 3]
    expected_output = [1, 2, 3, 3, 6, 6, 8]
    actual_output = quick_sort(input_list)

    print("输入:", input_list)
    print("预期输出:", expected_output)
    print("实际输出:", actual_output)

    # 示例 2：空列表
    empty_input = []
    empty_expected = []
    empty_actual = quick_sort(empty_input)

    print("输入:", empty_input)
    print("预期输出:", empty_expected)
    print("实际输出:", empty_actual)

    # 示例 3：重复元素
    duplicate_input = [5, 1, 5, 1, 5]
    duplicate_expected = [1, 1, 5, 5, 5]
    duplicate_actual = quick_sort(duplicate_input)

    print("输入:", duplicate_input)
    print("预期输出:", duplicate_expected)
    print("实际输出:", duplicate_actual)
```

示例运行结果：

```text
输入: [3, 6, 1, 8, 2, 6, 3]
预期输出: [1, 2, 3, 3, 6, 6, 8]
实际输出: [1, 2, 3, 3, 6, 6, 8]
输入: []
预期输出: []
实际输出: []
输入: [5, 1, 5, 1, 5]
预期输出: [1, 1, 5, 5, 5]
实际输出: [1, 1, 5, 5, 5]
```

关键逻辑说明：

- 基本情况：`len(nums) <= 1` 时直接返回副本，因此空列表可以正确处理。
- 基准选择：取中间元素作为 `pivot`，减少在已排序或接近已排序列表上的不平衡划分。
- 三路划分：`less`、`equal`、`greater` 分别保存小于、等于、大于基准的元素，因此重复元素不会被丢弃，也不会导致无限递归。
- 递归合并：`quick_sort(less) + equal + quick_sort(greater)` 保证最终结果按升序排列。
- 不修改原列表：返回新列表，原输入保持不变。