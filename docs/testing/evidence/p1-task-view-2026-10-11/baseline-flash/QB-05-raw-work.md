根据你的要求，下面是一个用 Python 实现的快速排序函数，支持空列表和重复元素，按整数升序排序，并附有注释和示例。

```python
def quick_sort(nums):
    """
    快速排序（升序）
    :param nums: 整数列表，允许为空或包含重复元素
    :return: 排序后的新列表（不修改原列表）
    """
    # 空列表或只有一个元素时，已经有序，直接返回
    if len(nums) <= 1:
        return nums[:]

    # 选择中间元素作为基准，避免有序输入时退化为最坏情况
    pivot = nums[len(nums) // 2]

    # 按基准划分：小于、等于、大于三部分，正确处理重复元素
    left = [x for x in nums if x < pivot]
    middle = [x for x in nums if x == pivot]
    right = [x for x in nums if x > pivot]

    # 递归排序左右两部分，中间部分已等于基准无需再排
    return quick_sort(left) + middle + quick_sort(right)


if __name__ == "__main__":
    # 示例 1：普通整数列表
    print(quick_sort([3, 1, 4, 1, 5, 9, 2, 6]))  # 预期输出: [1, 1, 2, 3, 4, 5, 6, 9]

    # 示例 2：空列表
    print(quick_sort([]))  # 预期输出: []

    # 示例 3：包含重复元素
    print(quick_sort([5, 3, 3, 1, 5, 2]))  # 预期输出: [1, 2, 3, 3, 5, 5]

    # 示例 4：已有序列表
    print(quick_sort([1, 2, 3, 4, 5]))  # 预期输出: [1, 2, 3, 4, 5]
```

说明：
- 使用三路划分（小于、等于、大于基准），重复元素不会造成额外递归，效率更稳定。
- 返回新列表，不修改传入的原列表。
- 空列表和单元素列表直接返回，保证边界情况正确。