#!/usr/bin/env python3
"""Create labels, milestones, issues and pins from docs/ISSUES-BACKLOG.md.

Usage:  python scripts/seed-github-issues.py --repo Akashiic/Akashic-Horizons-of-The-Mirai [--dry-run]

Requires the GitHub CLI (`gh`) authenticated with `repo` scope. Idempotent: issues whose title
already exists are skipped. Block format in the backlog:

    ## ISSUE-NN — title
    <!-- labels: a, b | milestone: Name | pin: yes -->
    body...
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

LABELS = {
    "tracker": ("5319e7", "Painel mestre"),
    "homologation": ("0e8a16", "Homologação / gates de certificação"),
    "bug": ("d73a4a", "Defeito observado"),
    "performance": ("fbca04", "Desempenho / memória"),
    "compatibility": ("1d76db", "Compatibilidade com outros mods/ambiente"),
    "infra": ("c5def5", "Infraestrutura, logs, build"),
    "release": ("0052cc", "Identidade, empacotamento, entrega"),
    "docs": ("0075ca", "Documentação / licenciamento"),
    "research": ("d4c5f9", "Investigação"),
    "needs-evidence": ("f9d0c4", "Falta evidência runtime"),
    "blocked": ("b60205", "Bloqueado por outro item"),
    "external": ("ededed", "Causa em projeto de terceiros"),
    "server": ("006b75", "Crucible / backend dedicado"),
    "p0": ("b60205", "Bloqueia a certificação 0.1.19"),
    "p1": ("d93f0b", "Deve entrar na 0.1.20"),
    "p2": ("fef2c0", "Melhoria / investigação"),
}
MILESTONES = {
    "0.1.19 — Homologação runtime": "Fechar a evidência runtime da 0.1.19-dev quiescence (Gates A/B/C, A/B visual, servidor).",
    "0.1.20 — Candidato": "Correções e melhorias decididas com dados, a partir de uma 0.1.19 homologada.",
    "Infra e documentação": "Logs, build reprodutível, licença, itens de terceiros.",
}
ROOT = Path(__file__).resolve().parent.parent
BACKLOG = ROOT / "docs" / "ISSUES-BACKLOG.md"


def gh(args, check=True, input_text=None):
    proc = subprocess.run(["gh"] + args, capture_output=True, text=True, encoding="utf-8", input=input_text)
    if check and proc.returncode != 0:
        sys.exit(f"gh {' '.join(args)}\n{proc.stderr}")
    return proc.stdout.strip()


def parse():
    text = BACKLOG.read_text(encoding="utf-8")
    blocks = re.split(r"(?m)^## ISSUE-(\d+) — (.+)$", text)
    issues = []
    for i in range(1, len(blocks), 3):
        num, title, rest = blocks[i], blocks[i + 1].strip(), blocks[i + 2]
        m = re.match(r"\s*<!--\s*labels:\s*(.*?)\s*\|\s*milestone:\s*(.*?)\s*\|\s*pin:\s*(\w+)\s*-->\s*", rest)
        if not m:
            sys.exit(f"ISSUE-{num}: metadata comment missing/invalid")
        issues.append({
            "code": f"AOTM-{num}",
            "title": f"[AOTM-{num}] {title}",
            "labels": [x.strip() for x in m.group(1).split(",") if x.strip()],
            "milestone": m.group(2).strip(),
            "pin": m.group(3).lower() == "yes",
            "body": rest[m.end():].strip() + f"\n\n---\n_Origem: `docs/ISSUES-BACKLOG.md` (AOTM-{num})._\n",
        })
    return issues


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    issues = parse()
    print(f"{len(issues)} issues parsed")
    if a.dry_run:
        for it in issues:
            print(it["title"], it["labels"], it["milestone"], "PIN" if it["pin"] else "")
        return

    for name, (color, desc) in LABELS.items():
        gh(["label", "create", name, "--repo", a.repo, "--color", color, "--description", desc, "--force"])
    existing_ms = json.loads(gh(["api", f"repos/{a.repo}/milestones?state=all&per_page=100"]) or "[]")
    have = {m["title"] for m in existing_ms}
    for title, desc in MILESTONES.items():
        if title not in have:
            gh(["api", f"repos/{a.repo}/milestones", "-f", f"title={title}", "-f", f"description={desc}"])

    current = json.loads(gh(["issue", "list", "--repo", a.repo, "--state", "all", "--limit", "300", "--json", "title,number"]) or "[]")
    by_title = {i["title"]: i["number"] for i in current}
    numbers = {}
    for it in issues:
        if it["title"] in by_title:
            numbers[it["code"]] = by_title[it["title"]]
            print("skip (exists):", it["title"])
            continue
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
            f.write(it["body"])
            body_path = f.name
        args = ["issue", "create", "--repo", a.repo, "--title", it["title"], "--body-file", body_path, "--milestone", it["milestone"]]
        for lb in it["labels"]:
            args += ["--label", lb]
        url = gh(args)
        numbers[it["code"]] = int(url.rstrip("/").split("/")[-1])
        print("created", url)

    # Link the tracker checklist to real issue numbers.
    tracker = next((i for i in issues if i["code"] == "AOTM-00"), None)
    if tracker and "AOTM-00" in numbers:
        body = tracker["body"]
        body = re.sub(r"- \[ \] (AOTM-(\d\d))",
                      lambda m: f"- [ ] #{numbers.get('AOTM-' + m.group(2), '?')} — {m.group(1)}", body)
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
            f.write(body)
            gh(["issue", "edit", str(numbers["AOTM-00"]), "--repo", a.repo, "--body-file", f.name])

    for it in issues:
        if it["pin"]:
            gh(["issue", "pin", str(numbers[it["code"]]), "--repo", a.repo], check=False)
            print("pinned", it["code"], "#", numbers[it["code"]])


if __name__ == "__main__":
    main()
