"""Synthetic integration tests; no Minecraft, native driver, or private logs required."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/analyze-mirai-gpu.py"
SPEC = importlib.util.spec_from_file_location("analyze_mirai_gpu", SCRIPT)
ANALYZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYZER)


def header(session="private-session-a", schema=2):
    return {"kind": "session", "session": session, "message": "schema=" + str(schema) + " private=hidden"}


def sample(sequence=4, capture=1, session="private-session-a", pass_kind="main", origin=100, cpu=2_000_000,
           gpu=1_000_000, delay=10_000_000, lod=True):
    return {"kind": "gpu_sample", "session": session, "elapsed_ms": origin + 100,
            "message": ("pass=" + pass_kind + " capture=" + str(capture) + " sequence=" + str(sequence)
                        + " capture_start_elapsed_ms=" + str(origin) + " readback_delay_ns=" + str(delay)
                        + " lod_stage=" + str(lod).lower() + " cpu_wall_ns=" + str(cpu)
                        + " gpu_completion_ns=PASS:" + str(gpu)
                        + (",OPAQUE:" + str(gpu // 2) if lod else ",TASKS:0") + ",")}


class AnalyzeGpuTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def log(self, name, records):
        path = self.root / name
        path.write_text("\n".join(json.dumps(r) if not isinstance(r, str) else r for r in records) + "\n", encoding="utf-8")
        return path

    def test_statistics_separate_pass_and_stage_presence(self):
        records = [header()]
        records += [sample(sequence=i * 4, origin=i, gpu=i * 1_000_000) for i in range(1, 21)]
        records += [sample(sequence=84, origin=30, lod=False, gpu=0),
                    sample(sequence=88, origin=31, pass_kind="deferred", gpu=7_000_000)]
        report = ANALYZER.analyze([self.log("one.log", records)])
        groups = {(g["pass"], g["lod_stage"]): g for g in report["groups"]}
        stage = groups[("main", True)]["gpu_completion"]["PASS"]
        self.assertEqual(stage, {"count": 20, "min_ms": 1, "mean_ms": 10.5, "p95_ms": 19, "max_ms": 20})
        self.assertEqual(groups[("main", False)]["gpu_completion"]["PASS"]["mean_ms"], 0)
        self.assertIsNone(groups[("main", True)]["gpu_completion"]["TRANSPARENT"]["mean_ms"])
        self.assertEqual(groups[("deferred", True)]["count"], 1)
        self.assertEqual(report["counts"]["accepted_samples"], 22)

    def test_rotated_inputs_replays_new_capture_and_sessions(self):
        one = sample()
        later = sample(capture=2, origin=500)
        other = sample(session="private-session-b")
        first = self.log("first.log", [header(), one, later])
        second = self.log("second.log", [one, later, header("private-session-b"), other])
        report = ANALYZER.analyze([second, first])
        self.assertEqual(report["counts"]["accepted_samples"], 3)
        self.assertEqual(report["counts"]["duplicate_replay_records"], 2)
        self.assertEqual(report["counts"]["sessions_with_samples"], 2)
        self.assertEqual(report["sessions"][0]["capture_count"], 2)
        self.assertNotIn("private-session", json.dumps(report))
        self.assertNotIn("private=hidden", json.dumps(report))

    def test_conflicting_duplicate_key_excludes_both_regardless_of_order(self):
        a = self.log("a.log", [header(), sample()])
        b = self.log("b.log", [sample(cpu=3_000_000)])
        for paths in ([a, b], [b, a]):
            report = ANALYZER.analyze(paths)
            self.assertEqual(report["status"], "unavailable")
            self.assertEqual(report["counts"]["conflicting_gpu_records"], 2)
            self.assertEqual(report["counts"]["accepted_samples"], 0)

    def test_invalid_records_and_durations_are_reported(self):
        good = sample()
        bad = []
        for old, new in [("cpu_wall_ns=2000000", "cpu_wall_ns=-1"),
                         ("OPAQUE:500000", "OPAQUE:1000001"),
                         ("lod_stage=true", "lod_stage=false"),
                         ("PASS:1000000,", ""),
                         ("capture=1 ", ""),
                         ("sequence=4", "sequence=4 sequence=8")]:
            bad.append({**good, "message": good["message"].replace(old, new)})
        report = ANALYZER.analyze([self.log("bad.log", [header(), "{truncated", [], *bad, good])])
        self.assertEqual(report["counts"]["malformed_json_lines"], 1)
        self.assertEqual(report["counts"]["non_object_json_lines"], 1)
        self.assertEqual(report["counts"]["rejected_gpu_records"], 6)
        self.assertEqual(report["counts"]["accepted_samples"], 1)
        self.assertEqual(sum(report["skipped_reasons"].values()), 6)

    def test_missing_or_old_schema_does_not_invent_measurements(self):
        for records, reason in [([sample()], "missing_schema_header"),
                                ([header(schema=1), sample()], "unsupported_or_conflicting_schema")]:
            report = ANALYZER.analyze([self.log("schema.log", records)])
            self.assertEqual(report["status"], "unavailable")
            self.assertEqual(report["groups"], [])
            self.assertEqual(report["skipped_reasons"][reason], 1)

    def test_cli_old_cpu_only_log_is_unavailable_and_private_metadata_stays_private(self):
        path = self.log("old.log", [header(schema=1), {"kind": "metadata", "message": "server=private.example username=hidden"},
                                    {"kind": "timing", "message": "LOD_RENDER_WALL count=1 mean_us=5000"}])
        out = self.root / "report.json"
        completed = subprocess.run([sys.executable, str(SCRIPT), str(path), "--out", str(out)],
                                   text=True, capture_output=True, check=True)
        compact = json.loads(completed.stdout)
        full = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(compact["status"], "unavailable")
        self.assertEqual(full["counts"]["gpu_records"], 0)
        self.assertEqual(full["groups"], [])
        self.assertNotIn("hidden", out.read_text(encoding="utf-8"))
        self.assertNotIn("private.example", completed.stdout)

    def test_output_cannot_overwrite_input(self):
        path = self.log("in.log", [header()])
        before = path.read_bytes()
        completed = subprocess.run([sys.executable, str(SCRIPT), str(path), "--out", str(path)],
                                   text=True, capture_output=True)
        self.assertEqual(completed.returncode, 2)
        self.assertEqual(path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
