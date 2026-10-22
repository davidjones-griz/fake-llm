#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import sys
from typing import Dict, List, Optional, Tuple

ID_KEYS = ("id", "item_id", "item", "itemid")
LABEL_KEYS = ("label", "human_label", "judge_label", "prediction", "value", "class")


def sniff_format(path: str) -> str:
    lower = path.lower()
    if lower.endswith(".json"):
        return "json"
    if lower.endswith(".csv"):
        return "csv"
    with open(path, "r", encoding="utf-8") as f:
        head = f.read(256).lstrip()
    if head.startswith("{") or head.startswith("["):
        return "json"
    return "csv"


def _pick_key(fieldnames: List[str], candidates: Tuple[str, ...]) -> Optional[str]:
    lower_map = {str(f).strip().lower(): f for f in fieldnames}
    for c in candidates:
        if c in lower_map:
            return lower_map[c]
    return None


def load_labels_csv(path: str) -> Dict[str, str]:
    result: Dict[str, str] = {}
    with open(path, "r", encoding="utf-8", newline="") as f:
        rows = list(csv.reader(f))
    if not rows:
        return result
    header = rows[0]
    id_key = _pick_key(header, ID_KEYS)
    label_key = _pick_key(header, LABEL_KEYS)
    if id_key is not None and label_key is not None:
        id_idx = header.index(id_key)
        label_idx = header.index(label_key)
        data_rows = rows[1:]
    else:
        id_idx, label_idx = 0, 1
        looks_like_header = _pick_key(header, ID_KEYS + LABEL_KEYS) is not None
        data_rows = rows[1:] if looks_like_header else rows
    for row in data_rows:
        if not row or len(row) <= max(id_idx, label_idx):
            continue
        item_id = row[id_idx].strip()
        label = row[label_idx].strip()
        if item_id == "":
            continue
        result[item_id] = label
    return result


def load_labels_json(path: str) -> Dict[str, str]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    result: Dict[str, str] = {}
    if isinstance(data, dict):
        for k, v in data.items():
            result[str(k)] = str(v)
        return result
    if isinstance(data, list):
        for entry in data:
            if not isinstance(entry, dict):
                continue
            keys_lower = {str(k).strip().lower(): k for k in entry.keys()}
            id_k = next((keys_lower[c] for c in ID_KEYS if c in keys_lower), None)
            label_k = next((keys_lower[c] for c in LABEL_KEYS if c in keys_lower), None)
            if id_k is None or label_k is None:
                continue
            result[str(entry[id_k])] = str(entry[label_k])
        return result
    raise ValueError(f"Unsupported JSON shape in {path}: expected an object or a list of objects")


def load_labels(path: str) -> Dict[str, str]:
    fmt = sniff_format(path)
    if fmt == "json":
        return load_labels_json(path)
    return load_labels_csv(path)


def build_confusion_matrix(
    human: Dict[str, str], judge: Dict[str, str], common_ids: List[str]
) -> Tuple[List[str], Dict[str, Dict[str, int]]]:
    classes = sorted(set(human[i] for i in common_ids) | set(judge[i] for i in common_ids))
    matrix = {c: {c2: 0 for c2 in classes} for c in classes}
    for i in common_ids:
        matrix[human[i]][judge[i]] += 1
    return classes, matrix


def total_count(classes: List[str], matrix: Dict[str, Dict[str, int]]) -> int:
    return sum(matrix[c][c2] for c in classes for c2 in classes)


def raw_accuracy(classes: List[str], matrix: Dict[str, Dict[str, int]]) -> float:
    n = total_count(classes, matrix)
    if n == 0:
        return 0.0
    correct = sum(matrix[c][c] for c in classes)
    return correct / n


def cohens_kappa(classes: List[str], matrix: Dict[str, Dict[str, int]]) -> float:
    n = total_count(classes, matrix)
    if n == 0:
        return float("nan")
    po = sum(matrix[c][c] for c in classes) / n
    pe = 0.0
    for c in classes:
        row_total = sum(matrix[c][c2] for c2 in classes)
        col_total = sum(matrix[c2][c] for c2 in classes)
        pe += (row_total * col_total) / (n * n)
    if pe >= 1.0:
        return 1.0 if po >= 1.0 else 0.0
    return (po - pe) / (1 - pe)


def precision_recall_per_class(
    classes: List[str], matrix: Dict[str, Dict[str, int]]
) -> Dict[str, Tuple[Optional[float], Optional[float]]]:
    result: Dict[str, Tuple[Optional[float], Optional[float]]] = {}
    for c in classes:
        tp = matrix[c][c]
        col_total = sum(matrix[c2][c] for c2 in classes)
        row_total = sum(matrix[c][c2] for c2 in classes)
        precision = (tp / col_total) if col_total > 0 else None
        recall = (tp / row_total) if row_total > 0 else None
        result[c] = (precision, recall)
    return result


def format_confusion_matrix_text(classes: List[str], matrix: Dict[str, Dict[str, int]]) -> str:
    col_width = max([len(c) for c in classes] + [5])
    label_width = max([len(c) for c in classes] + [len("true\\pred")])
    header = "true\\pred".ljust(label_width) + " | " + " | ".join(c.rjust(col_width) for c in classes)
    lines = [header, "-" * len(header)]
    for c in classes:
        row = c.ljust(label_width) + " | " + " | ".join(
            str(matrix[c][c2]).rjust(col_width) for c2 in classes
        )
        lines.append(row)
    return "\n".join(lines)


def format_precision_recall_text(
    classes: List[str], pr: Dict[str, Tuple[Optional[float], Optional[float]]]
) -> str:
    label_width = max([len(c) for c in classes] + [len("class")])
    lines = ["class".ljust(label_width) + "precision".rjust(12) + "recall".rjust(12)]
    for c in classes:
        p, r = pr[c]
        p_s = f"{p:.4f}" if p is not None else "n/a"
        r_s = f"{r:.4f}" if r is not None else "n/a"
        lines.append(c.ljust(label_width) + p_s.rjust(12) + r_s.rjust(12))
    return "\n".join(lines)


def render_text_report(
    classes: List[str],
    matrix: Dict[str, Dict[str, int]],
    acc: float,
    kappa: float,
    pr: Dict[str, Tuple[Optional[float], Optional[float]]],
    missing_from_judge: List[str],
    missing_from_labels: List[str],
) -> str:
    n = total_count(classes, matrix)
    kind = "binary" if len(classes) == 2 else "multi-class"
    lines = [
        f"items scored: {n}",
        f"classes: {len(classes)} ({kind}) -> {', '.join(classes)}",
        f"raw accuracy: {acc:.4f}",
        f"cohen's kappa: {kappa:.4f}",
        "",
        "confusion matrix (rows = human/true, cols = judge/predicted)",
        format_confusion_matrix_text(classes, matrix),
        "",
        "per-class precision / recall",
        format_precision_recall_text(classes, pr),
    ]
    if missing_from_judge:
        lines.append("")
        lines.append(f"skipped (in labels, missing from judge output): {len(missing_from_judge)}")
    if missing_from_labels:
        lines.append("")
        lines.append(f"skipped (in judge output, missing from labels): {len(missing_from_labels)}")
    return "\n".join(lines)


def render_markdown_report(
    classes: List[str],
    matrix: Dict[str, Dict[str, int]],
    acc: float,
    kappa: float,
    pr: Dict[str, Tuple[Optional[float], Optional[float]]],
    missing_from_judge: List[str],
    missing_from_labels: List[str],
) -> str:
    n = total_count(classes, matrix)
    kind = "binary" if len(classes) == 2 else "multi-class"
    lines = [
        "# Judge Agreement Report",
        "",
        f"- Items scored: {n}",
        f"- Classes: {len(classes)} ({kind}) -> {', '.join(classes)}",
        f"- Raw accuracy: {acc:.4f}",
        f"- Cohen's kappa: {kappa:.4f}",
    ]
    if missing_from_judge:
        lines.append(f"- Skipped (in labels, missing from judge output): {len(missing_from_judge)}")
    if missing_from_labels:
        lines.append(f"- Skipped (in judge output, missing from labels): {len(missing_from_labels)}")
    lines.append("")
    lines.append("## Confusion matrix")
    lines.append("")
    lines.append("Rows = human/true label, columns = judge/predicted label.")
    lines.append("")
    header = "| true \\ pred | " + " | ".join(classes) + " |"
    sep = "| --- | " + " | ".join(["---"] * len(classes)) + " |"
    lines.append(header)
    lines.append(sep)
    for c in classes:
        row = f"| {c} | " + " | ".join(str(matrix[c][c2]) for c2 in classes) + " |"
        lines.append(row)
    lines.append("")
    lines.append("## Per-class precision / recall")
    lines.append("")
    lines.append("| class | precision | recall |")
    lines.append("| --- | --- | --- |")
    for c in classes:
        p, r = pr[c]
        p_s = f"{p:.4f}" if p is not None else "n/a"
        r_s = f"{r:.4f}" if r is not None else "n/a"
        lines.append(f"| {c} | {p_s} | {r_s} |")
    lines.append("")
    return "\n".join(lines)


def run(labels_path: str, judge_path: str, out_path: Optional[str] = None) -> int:
    human = load_labels(labels_path)
    judge = load_labels(judge_path)

    common_ids = sorted(set(human) & set(judge))
    missing_from_judge = sorted(set(human) - set(judge))
    missing_from_labels = sorted(set(judge) - set(human))

    if not common_ids:
        print("No overlapping item ids between labels and judge output.", file=sys.stderr)
        return 1

    classes, matrix = build_confusion_matrix(human, judge, common_ids)
    acc = raw_accuracy(classes, matrix)
    kappa = cohens_kappa(classes, matrix)
    pr = precision_recall_per_class(classes, matrix)

    text = render_text_report(classes, matrix, acc, kappa, pr, missing_from_judge, missing_from_labels)
    print(text)

    if out_path:
        md = render_markdown_report(classes, matrix, acc, kappa, pr, missing_from_judge, missing_from_labels)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(md)
        print(f"\nmarkdown report written to {out_path}", file=sys.stderr)

    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Confusion matrix, raw accuracy, Cohen's kappa, and per-class precision/recall "
        "between a human-labels file and a judge-output file (CSV or JSON, item id -> label)."
    )
    parser.add_argument("--labels", required=True, help="Path to the human labels CSV or JSON.")
    parser.add_argument("--judge", required=True, help="Path to the judge-output CSV or JSON.")
    parser.add_argument("--out", default=None, help="Optional path to write a Markdown report.")
    args = parser.parse_args(argv)

    try:
        return run(args.labels, args.judge, args.out)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
