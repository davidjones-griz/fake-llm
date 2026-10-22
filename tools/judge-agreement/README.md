# judge-agreement

Compares a human-labels file with a judge-output file. Items are scored only where the two files share an id. The report is a confusion matrix (rows are the human label, columns are the judge label), raw accuracy, Cohen's kappa, and per-class precision and recall.

```bash
python tools/judge-agreement/agreement.py \
  --labels labels.csv \
  --judge judge.csv \
  --out report.md
```

`--labels` and `--judge` are required. `--out` is optional. The text report is printed to stdout. When `--out` is set, the same numbers are written as Markdown to that path, and a one-line note goes to stderr.

Exit status is 0 when at least one id overlaps, 1 when no ids overlap, and 2 when a file cannot be read or parsed.

## Input files

A `.json` suffix is JSON. A `.csv` suffix is CSV. Any other name is sniffed from the first 256 characters: a file that starts with `{` or `[` is JSON, and everything else is CSV.

CSV columns are chosen from the header, case-insensitively. The id column is the first of `id`, `item_id`, `item`, `itemid`. The label column is the first of `label`, `human_label`, `judge_label`, `prediction`, `value`, `class`. If those two columns are not both present, the first two columns are the id and the label. A first row that contains any of those known names is skipped as a header. Otherwise the first row is data. Values are stripped. An empty id is skipped. A repeated id keeps the later row.

JSON is either an object of id to label, or a list of objects using the same id and label key names as CSV. Keys and values are read as strings. A list entry that has no id key or no label key is skipped. Any other JSON value is an error.

Ids that appear in only one file are counted in the report and left out of the scores. Classes are the sorted set of labels on the overlapping ids. With two classes the report says binary; otherwise it says multi-class.

Accuracy is the share of overlapping items whose labels match. Kappa is Cohen's kappa on that table. Precision for a class is the diagonal count divided by the judge's predictions of that class. Recall is the diagonal count divided by the human labels of that class. A class with no predictions, or no human labels, is `n/a` for that rate.

The tests are standard-library `unittest`:

```bash
python -m unittest
```

Run that from `tools/judge-agreement`.
