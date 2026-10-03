-- When the user answered this row in Quick sort (QuickSortService.answer). Lets the usage counters
-- tell an answer the user later changed (finora.quick_sort.answer_changed_later) from any other edit.
ALTER TABLE transactions ADD COLUMN quick_sorted_at TIMESTAMPTZ;
