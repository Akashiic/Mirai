#!/usr/bin/env python3
"""Analyze Mirai schema-2 GPU samples without reproducing private log metadata.

Usage: python scripts/analyze-mirai-gpu.py latest.log previous-1.log --out gpu-report.json
Run tests: python -m unittest discover -s tests/diagnostics -p test_analyze_mirai_gpu.py

Only explicit input paths are read. stdout contains a compact JSON summary; --out
writes the full sanitized report. Missing samples/stages are unavailable, not zero.
SPDX-License-Identifier: LGPL-3.0-only
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


STAGES = ("PASS", "TASKS", "OPAQUE", "GENERIC_SSAO", "SSAO", "GENERIC",
          "TRANSPARENT", "FOG", "FAR_FADE", "AA", "COMPOSITE")
FIELDS = {"pass", "capture", "sequence", "capture_start_elapsed_ms",
          "readback_delay_ns", "lod_stage", "cpu_wall_ns", "gpu_completion_ns"}
MAX_LONG = (1 << 63) - 1
NOTES = [
    "Samples occur every fourth invocation of each main/deferred pass kind independently; this is not an FPS trace.",
    "GPU completion intervals cover the instrumented ClientApi pass/stages, not the entire GPU frame or exclusive GPU utilization; command starvation may contribute.",
    "CPU wall and GPU intervals overlap and must not be added. Stages and PASS must not be added.",
    "lod_stage=true means an OPAQUE or TRANSPARENT stage was observed, not proof that geometry was drawn.",
    "Statistics describe accepted samples only, not all frames. p95 uses nearest rank ceil(0.95*N), without interpolation.",
    "Origin elapsed time is distinct from publication/readback time. Session aliases reveal no original session identifiers.",
    "Replays are deduplicated by session/capture/pass/sequence; conflicting payloads exclude the whole key. Rotated inputs may be supplied in any order.",
    "A schema=2 session header must be present in at least one supplied segment for each analyzed session.",
]


class InvalidSample(ValueError):
    pass


def integer(text, name, minimum=0):
    if not isinstance(text, str) or not re.fullmatch(r"[0-9]+", text):
        raise InvalidSample("invalid_" + name)
    # Bound digit count before int(), including hostile very long strings.
    if len(text) > 19:
        raise InvalidSample("invalid_" + name)
    value = int(text)
    if not minimum <= value <= MAX_LONG:
        raise InvalidSample("invalid_" + name)
    return value


def parse_sample(record):
    session = record.get("session")
    if not isinstance(session, str) or not session or len(session) > 128:
        raise InvalidSample("invalid_session")
    message = record.get("message")
    if not isinstance(message, str):
        raise InvalidSample("invalid_message")
    fields = {}
    for item in message.split():
        key, separator, value = item.partition("=")
        if not separator or key in fields:
            raise InvalidSample("invalid_or_duplicate_field")
        fields[key] = value
    if set(fields) != FIELDS:
        raise InvalidSample("unsupported_sample_fields")
    if fields["pass"] not in ("main", "deferred"):
        raise InvalidSample("invalid_pass")
    if fields["lod_stage"] not in ("true", "false"):
        raise InvalidSample("invalid_lod_stage")
    sample = {"session": session, "pass": fields["pass"],
              "lod_stage": fields["lod_stage"] == "true"}
    for field in ("capture", "sequence", "capture_start_elapsed_ms", "readback_delay_ns", "cpu_wall_ns"):
        sample[field] = integer(fields[field], field, 1 if field in ("capture", "sequence") else 0)
    elapsed = record.get("elapsed_ms")
    if type(elapsed) is not int or not 0 <= elapsed <= MAX_LONG:
        raise InvalidSample("invalid_publication_elapsed_ms")
    if sample["capture_start_elapsed_ms"] > elapsed:
        raise InvalidSample("origin_after_publication")
    stages = {}
    # The current facade writes a final comma; accept either representation.
    for item in fields["gpu_completion_ns"].removesuffix(",").split(","):
        stage, separator, duration = item.partition(":")
        if not separator or stage not in STAGES or stage in stages:
            raise InvalidSample("invalid_or_duplicate_stage")
        stages[stage] = integer(duration, "stage_duration")
    if "PASS" not in stages:
        raise InvalidSample("missing_pass_stage")
    if any(duration > stages["PASS"] for duration in stages.values()):
        raise InvalidSample("stage_exceeds_pass")
    if sample["lod_stage"] != ("OPAQUE" in stages or "TRANSPARENT" in stages):
        raise InvalidSample("inconsistent_lod_stage")
    sample["stages"] = stages
    return sample


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("non-finite JSON constant")


def stats(values):
    if not values:
        return {"count": 0, "min_ms": None, "mean_ms": None, "p95_ms": None, "max_ms": None}
    ordered = sorted(values)
    return {"count": len(ordered), "min_ms": ordered[0] / 1_000_000,
            "mean_ms": statistics.mean(ordered) / 1_000_000,
            "p95_ms": ordered[math.ceil(0.95 * len(ordered)) - 1] / 1_000_000,
            "max_ms": ordered[-1] / 1_000_000}


def grouped(samples):
    result = []
    for pass_kind in ("main", "deferred"):
        for lod_stage in (False, True):
            selected = [s for s in samples if s["pass"] == pass_kind and s["lod_stage"] == lod_stage]
            if not selected:
                continue
            result.append({
                "pass": pass_kind, "lod_stage": lod_stage, "count": len(selected),
                "cpu_wall": stats([s["cpu_wall_ns"] for s in selected]),
                "readback_delay": stats([s["readback_delay_ns"] for s in selected]),
                "gpu_completion": {stage: stats([s["stages"][stage] for s in selected if stage in s["stages"]])
                                   for stage in STAGES},
            })
    return result


def analyze(paths):
    sources, candidates = [], []
    schemas = collections.defaultdict(set)
    counts = collections.Counter()
    skips = collections.Counter()
    examples = []

    def skip(reason, source, line):
        skips[reason] += 1
        if len(examples) < 20:
            examples.append({"input_index": source, "line": line, "reason": reason})

    for source, path in enumerate(paths, 1):
        digest = hashlib.sha256()
        byte_count = line_count = 0
        with Path(path).open("rb") as stream:
            for line_count, raw in enumerate(stream, 1):
                digest.update(raw)
                byte_count += len(raw)
                counts["lines"] += 1
                if not raw.strip():
                    counts["blank_lines"] += 1
                    continue
                try:
                    record = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=strict_object,
                                        parse_constant=reject_constant)
                except (UnicodeError, ValueError):
                    counts["malformed_json_lines"] += 1
                    continue
                if not isinstance(record, dict):
                    counts["non_object_json_lines"] += 1
                    continue
                if record.get("kind") == "session":
                    session, message = record.get("session"), record.get("message")
                    match = re.match(r"schema=([0-9]+)(?:\s|$)", message) if isinstance(message, str) else None
                    if isinstance(session, str) and session and len(session) <= 128 and match:
                        schemas[session].add(match.group(1))
                if record.get("kind") != "gpu_sample":
                    counts["non_gpu_records"] += 1
                    continue
                counts["gpu_records"] += 1
                try:
                    candidates.append((parse_sample(record), source, line_count))
                except InvalidSample as failure:
                    skip(str(failure), source, line_count)
        sources.append({"input_index": source, "bytes": byte_count, "lines": line_count,
                        "sha256": digest.hexdigest()})

    unique = collections.defaultdict(dict)
    for sample, source, line in candidates:
        declared = schemas.get(sample["session"], set())
        if declared != {"2"}:
            skip("missing_schema_header" if not declared else "unsupported_or_conflicting_schema", source, line)
            continue
        key = (sample["session"], sample["capture"], sample["pass"], sample["sequence"])
        # Publication timestamps are deliberately omitted: replay may be logged later.
        fingerprint = json.dumps(sample, sort_keys=True, separators=(",", ":"))
        entry = unique[key].setdefault(fingerprint, {"sample": sample, "records": 0})
        entry["records"] += 1

    samples = []
    for payloads in unique.values():
        if len(payloads) > 1:
            counts["conflicting_keys"] += 1
            counts["conflicting_gpu_records"] += sum(item["records"] for item in payloads.values())
        else:
            entry = next(iter(payloads.values()))
            counts["duplicate_replay_records"] += entry["records"] - 1
            samples.append(entry["sample"])
    samples.sort(key=lambda s: (s["session"], s["capture_start_elapsed_ms"], s["capture"], s["sequence"], s["pass"]))
    sessions = []
    for index, session in enumerate(sorted({s["session"] for s in samples}), 1):
        selected = [s for s in samples if s["session"] == session]
        sessions.append({"session_alias": "session_" + str(index), "count": len(selected),
                         "capture_count": len({s["capture"] for s in selected}),
                         "origin_elapsed_ms": {"first": selected[0]["capture_start_elapsed_ms"],
                                               "last": selected[-1]["capture_start_elapsed_ms"]},
                         "groups": grouped(selected)})
    names = ("lines", "blank_lines", "malformed_json_lines", "non_object_json_lines", "non_gpu_records",
             "gpu_records", "duplicate_replay_records", "conflicting_keys", "conflicting_gpu_records")
    return {"report_schema": 1, "status": "available" if samples else "unavailable",
            "notes": NOTES, "sources": sources,
            "counts": {**{name: counts[name] for name in names}, "accepted_samples": len(samples),
                       "sessions_with_samples": len(sessions), "rejected_gpu_records": sum(skips.values())},
            "skipped_reasons": dict(sorted(skips.items())), "skipped_examples": examples,
            "groups": grouped(samples), "sessions": sessions}


def compact(report):
    return {"status": report["status"], "counts": report["counts"],
            "skipped_reasons": report["skipped_reasons"],
            "groups": [{"pass": g["pass"], "lod_stage": g["lod_stage"], "count": g["count"],
                        "cpu_wall_mean_ms": g["cpu_wall"]["mean_ms"],
                        "gpu_pass_mean_ms": g["gpu_completion"]["PASS"]["mean_ms"],
                        "gpu_pass_p95_ms": g["gpu_completion"]["PASS"]["p95_ms"]} for g in report["groups"]],
            "scope": "Sampled Mirai pass completion intervals; not full-frame GPU time. CPU/GPU are not additive."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+", type=Path, help="Explicit JSONL log segments (schema-2 headers required).")
    parser.add_argument("--out", type=Path, help="Full sanitized JSON report; stdout always remains a compact summary.")
    args = parser.parse_args(argv)
    if args.out and args.out.resolve() in {path.resolve() for path in args.inputs}:
        parser.error("--out must not overwrite an input")
    try:
        report = analyze(args.inputs)
        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError:
        print(json.dumps({"status": "error", "reason": "input_read_or_report_write_failed"}))
        return 2
    print(json.dumps(compact(report), ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
