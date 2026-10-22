from __future__ import annotations

import csv
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))

import agreement  # noqa: E402


class PerfectAgreementTest(unittest.TestCase):
    def test_perfect_agreement_kappa_is_one(self) -> None:
        human = {}
        judge = {}
        for i in range(5):
            item = f"p{i}"
            human[item] = "yes"
            judge[item] = "yes"
        for i in range(5):
            item = f"n{i}"
            human[item] = "no"
            judge[item] = "no"
        common_ids = sorted(human)
        classes, matrix = agreement.build_confusion_matrix(human, judge, common_ids)
        self.assertEqual(classes, ["no", "yes"])
        self.assertEqual(matrix["yes"]["yes"], 5)
        self.assertEqual(matrix["no"]["no"], 5)
        self.assertEqual(matrix["yes"]["no"], 0)
        self.assertEqual(matrix["no"]["yes"], 0)
        self.assertEqual(agreement.raw_accuracy(classes, matrix), 1.0)
        self.assertAlmostEqual(agreement.cohens_kappa(classes, matrix), 1.0, places=9)


class ChanceAgreementTest(unittest.TestCase):
    def test_chance_agreement_kappa_is_zero(self) -> None:
        human = {
            "i1": "A", "i2": "A",
            "i3": "B", "i4": "B",
        }
        judge = {
            "i1": "A", "i2": "B",
            "i3": "A", "i4": "B",
        }
        common_ids = sorted(human)
        classes, matrix = agreement.build_confusion_matrix(human, judge, common_ids)
        self.assertEqual(matrix["A"]["A"], 1)
        self.assertEqual(matrix["A"]["B"], 1)
        self.assertEqual(matrix["B"]["A"], 1)
        self.assertEqual(matrix["B"]["B"], 1)
        acc = agreement.raw_accuracy(classes, matrix)
        kappa = agreement.cohens_kappa(classes, matrix)
        self.assertAlmostEqual(acc, 0.5, places=9)
        self.assertAlmostEqual(kappa, 0.0, places=9)


class ClassImbalanceAnswerKeyTest(unittest.TestCase):
    def test_1tp_34tn_10fp_0fn_class_imbalance_example(self) -> None:
        human = {}
        judge = {}
        idx = 0
        for _ in range(1):
            human[f"i{idx}"] = "positive"
            judge[f"i{idx}"] = "positive"
            idx += 1
        for _ in range(34):
            human[f"i{idx}"] = "negative"
            judge[f"i{idx}"] = "negative"
            idx += 1
        for _ in range(10):
            human[f"i{idx}"] = "negative"
            judge[f"i{idx}"] = "positive"
            idx += 1
        for _ in range(0):
            human[f"i{idx}"] = "positive"
            judge[f"i{idx}"] = "negative"
            idx += 1
        self.assertEqual(len(human), 45)

        common_ids = sorted(human)
        classes, matrix = agreement.build_confusion_matrix(human, judge, common_ids)
        self.assertEqual(classes, ["negative", "positive"])
        self.assertEqual(matrix["positive"]["positive"], 1)
        self.assertEqual(matrix["negative"]["negative"], 34)
        self.assertEqual(matrix["negative"]["positive"], 10)
        self.assertEqual(matrix["positive"]["negative"], 0)

        acc = agreement.raw_accuracy(classes, matrix)
        self.assertAlmostEqual(acc, 35 / 45, places=9)
        self.assertAlmostEqual(round(acc, 3), 0.778, places=9)

        kappa = agreement.cohens_kappa(classes, matrix)
        self.assertAlmostEqual(kappa, 34 / 259, places=9)
        self.assertLess(kappa, 0.15)
        self.assertLess(kappa, acc / 2)

        pr = agreement.precision_recall_per_class(classes, matrix)
        pos_precision, pos_recall = pr["positive"]
        neg_precision, neg_recall = pr["negative"]
        self.assertAlmostEqual(pos_precision, 1 / 11, places=9)
        self.assertAlmostEqual(pos_recall, 1.0, places=9)
        self.assertAlmostEqual(neg_precision, 1.0, places=9)
        self.assertAlmostEqual(neg_recall, 34 / 44, places=9)


class MultiClassKappaTest(unittest.TestCase):
    def test_three_class_hand_computed_kappa(self) -> None:
        human = {}
        judge = {}
        idx = 0
        for _ in range(3):
            human[f"i{idx}"] = "A"; judge[f"i{idx}"] = "A"; idx += 1
        for _ in range(1):
            human[f"i{idx}"] = "A"; judge[f"i{idx}"] = "B"; idx += 1
        for _ in range(1):
            human[f"i{idx}"] = "B"; judge[f"i{idx}"] = "A"; idx += 1
        for _ in range(3):
            human[f"i{idx}"] = "B"; judge[f"i{idx}"] = "B"; idx += 1
        for _ in range(2):
            human[f"i{idx}"] = "C"; judge[f"i{idx}"] = "C"; idx += 1
        self.assertEqual(len(human), 10)

        common_ids = sorted(human)
        classes, matrix = agreement.build_confusion_matrix(human, judge, common_ids)
        self.assertEqual(classes, ["A", "B", "C"])

        acc = agreement.raw_accuracy(classes, matrix)
        self.assertAlmostEqual(acc, 0.8, places=9)

        kappa = agreement.cohens_kappa(classes, matrix)
        self.assertAlmostEqual(kappa, 0.6875, places=9)


class LoaderTest(unittest.TestCase):
    def test_csv_loader_roundtrip(self) -> None:
        with TemporaryDirectory() as d:
            path = Path(d) / "labels.csv"
            with open(path, "w", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                w.writerow(["id", "label"])
                w.writerow(["a", "yes"])
                w.writerow(["b", "no"])
            loaded = agreement.load_labels(str(path))
            self.assertEqual(loaded, {"a": "yes", "b": "no"})

    def test_csv_loader_accepts_judge_label_header(self) -> None:
        with TemporaryDirectory() as d:
            path = Path(d) / "judge.csv"
            with open(path, "w", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                w.writerow(["item_id", "judge_label"])
                w.writerow(["a", "yes"])
            loaded = agreement.load_labels(str(path))
            self.assertEqual(loaded, {"a": "yes"})

    def test_json_loader_dict_form(self) -> None:
        with TemporaryDirectory() as d:
            path = Path(d) / "labels.json"
            path.write_text(json.dumps({"a": "yes", "b": "no"}), encoding="utf-8")
            loaded = agreement.load_labels(str(path))
            self.assertEqual(loaded, {"a": "yes", "b": "no"})

    def test_json_loader_list_of_objects_form(self) -> None:
        with TemporaryDirectory() as d:
            path = Path(d) / "labels.json"
            payload = [{"id": "a", "label": "yes"}, {"id": "b", "label": "no"}]
            path.write_text(json.dumps(payload), encoding="utf-8")
            loaded = agreement.load_labels(str(path))
            self.assertEqual(loaded, {"a": "yes", "b": "no"})


class MismatchedIdsTest(unittest.TestCase):
    def test_scored_only_on_overlap(self) -> None:
        human = {"a": "yes", "b": "no", "c": "yes"}
        judge = {"a": "yes", "b": "no", "d": "no"}
        common_ids = sorted(set(human) & set(judge))
        self.assertEqual(common_ids, ["a", "b"])
        classes, matrix = agreement.build_confusion_matrix(human, judge, common_ids)
        self.assertEqual(agreement.total_count(classes, matrix), 2)
        self.assertEqual(agreement.raw_accuracy(classes, matrix), 1.0)

    def test_no_overlap_run_returns_nonzero(self) -> None:
        with TemporaryDirectory() as d:
            labels_path = Path(d) / "labels.json"
            judge_path = Path(d) / "judge.json"
            labels_path.write_text(json.dumps({"a": "yes"}), encoding="utf-8")
            judge_path.write_text(json.dumps({"z": "yes"}), encoding="utf-8")
            rc = agreement.run(str(labels_path), str(judge_path))
            self.assertEqual(rc, 1)


class CliEndToEndTest(unittest.TestCase):
    def test_main_writes_markdown_report(self) -> None:
        with TemporaryDirectory() as d:
            labels_path = Path(d) / "labels.csv"
            judge_path = Path(d) / "judge.csv"
            out_path = Path(d) / "report.md"
            with open(labels_path, "w", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                w.writerow(["id", "label"])
                w.writerow(["a", "yes"])
                w.writerow(["b", "no"])
                w.writerow(["c", "yes"])
            with open(judge_path, "w", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                w.writerow(["id", "label"])
                w.writerow(["a", "yes"])
                w.writerow(["b", "yes"])
                w.writerow(["c", "yes"])
            rc = agreement.main(
                ["--labels", str(labels_path), "--judge", str(judge_path), "--out", str(out_path)]
            )
            self.assertEqual(rc, 0)
            self.assertTrue(out_path.exists())
            content = out_path.read_text(encoding="utf-8")
            self.assertIn("Judge Agreement Report", content)
            self.assertIn("Cohen's kappa", content)
            self.assertIn("Confusion matrix", content)


if __name__ == "__main__":
    unittest.main()
