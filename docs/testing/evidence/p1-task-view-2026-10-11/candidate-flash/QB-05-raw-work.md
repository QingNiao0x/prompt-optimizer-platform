根据你的要求，下面给出一个用 Python 实现的快速排序函数，支持整数列表升序排序，并处理空列表和重复元素。

```python
def quick_sort(nums):
    """
    快速排序（升序）
    :param nums: List[int]，待排序的整数列表
    :return: List[int]，排序后的新列表
    """
    # 空列表或只有一个元素时，已经有序，直接返回
    if len(nums) <= 1:
        return nums[:]

    # 选择中间元素作为基准值，避免对已排序列表表现过差
    pivot = nums[len(nums) // 2]

    # 分成三部分：小于基准、等于基准、大于基准
    # 这样能正确处理重复元素
    left = [x for x in nums if x < pivot]
    middle = [x for x in nums if x == pivot]
    right = [x for x in nums if x > pivot]

    # 递归排序左右两部分，再拼接起来
    return quick_sort(left) + middle + quick_sort(right)


if __name__ == "__main__":
    # 示例 1：普通整数列表
    print(quick_sort([3, 1, 4, 1, 5, 9, 2, 6]))  # 预期输出: [1, 1, 2, 3, 4, 5, 6, 9]

    # 示例 2：空列表
    print(quick_sort([]))  # 预期输出: []

    # 示例 3：包含重复元素
    print(quick_sort([5, 3, 3, 1, 5, 2]))  # 预期输出: [1, 2, 3, 3, 5, 5]

    # 示例 4：已经有序
    print(quick_sort([1, 2, 3, 4]))  # 预期输出: [1, 2, 3, 4]
```

说明：
- 使用 `len(nums) <= 1` 处理空列表和单元素列表。
- 采用三路划分（小于、等于、大于基准），重复元素会被集中到 `middle`，不会反复参与递归。
- 返回新列表，不修改原列表。
- 基准取中间元素，降低对已排序输入退化到最坏情况的概率。