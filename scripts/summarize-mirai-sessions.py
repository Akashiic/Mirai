#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-only
"""Summarise Akashic Mirai runtime logs (logs/akashic-mirai/*.log, JSON-lines, schema=2).

Produces a *sanitised* per-session summary (no player name, address, world name or
JVM arguments are ever read because the Mirai logger never writes them) and, in
addition, a per-5-second-window split between "renderer ACTIVE" windows
(LOD_RENDER_WALL count > 0) and the rest.

Usage:
  python scripts/summarize-mirai-sessions.py --log-dir <.../logs/akashic-mirai> \
      [--out-json summary.json] [--out-md summary.md]

Caveats (same as docs/DIAGNOSTICO-MIRAI.md): every *_WALL timing is elapsed wall time
including waits, nested timings overlap and MUST NOT be summed, p95_bucket_ms is a
histogram bucket ceiling and not an exact percentile, and the logger keeps only
latest.log + 4 previous segments (about 20 MiB) so old sessions are overwritten.
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

TIMING_RE = re.compile(r'(\w+) count=(\d+) mean_us=(\d+) max_us=(\d+) p95_bucket_ms=(\S+)')
VBO_RE = re.compile(r'VBO Render Count: \[(\d+)/(\d+)\]')
MODE_RE = re.compile(r'config\.renderer_mode=(\w+)')
KEEP_MODS = ('angelica', 'hodgepodge', 'coretweaks', 'archaicfix', 'entityculling', 'gtnhlib', 'unimixins',
             'cubicchunks', 'endlessids', 'thepatcher', 'playerapi', 'backhand')


def segment_order(path):
    name = os.path.basename(path)
    m = re.match(r'previous-(\d+)\.log$', name)
    if m:
        return (0, -int(m.group(1)))  # previous-4 (oldest) ... previous-1
    return (1, 0)  # latest.log last


def load(log_dir):
    files = sorted(glob.glob(os.path.join(log_dir, '*.log')), key=segment_order)
    for path in files:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            for line in f:
                try:
                    obj = json.loads(line)
                except ValueError:
                    continue
                yield os.path.basename(path), obj


def summarise(log_dir):
    sessions = collections.OrderedDict()
    windows = collections.OrderedDict()
    vbo = {}
    for fn, o in load(log_dir):
        sid = (o.get('session') or '')[:8]
        k = o.get('kind')
        m = o.get('message', '')
        s = sessions.setdefault(sid, {
            'segments': set(), 'first_utc': o.get('utc'), 'last_utc': o.get('utc'), 'identity': {},
            'mods': {}, 'modes': [], 'timing': {}, 'counters': collections.Counter(), 'errors': [],
            'ends': [], 'jvm': {}, 'vbo_max': [0, 0], 'client_samples': collections.Counter()})
        s['segments'].add(fn)
        s['last_utc'] = o.get('utc')
        if k == 'metadata':
            mm = re.match(r'identity\.(\w+)=(.*)', m)
            if mm:
                s['identity'][mm.group(1)] = mm.group(2)
            if m.startswith('mod.') and any(x in m.lower() for x in KEEP_MODS):
                key, val = m[4:].split('=', 1)
                s['mods'][key] = val
            if m.startswith('environment.'):
                key, val = m[len('environment.'):].split('=', 1)
                if key in ('java.version', 'java.vendor', 'java.vm.name', 'os.arch', 'processors'):
                    s['identity']['env.' + key] = val
        mm = MODE_RE.search(m)
        if mm and (not s['modes'] or s['modes'][-1][1] != mm.group(1)):
            s['modes'].append([o.get('utc'), mm.group(1)])
        v = VBO_RE.search(m)
        if v:
            pair = [int(v.group(1)), int(v.group(2))]
            vbo[(sid, (o.get('utc') or '')[:19])] = pair
            if pair > s['vbo_max']:
                s['vbo_max'] = pair
        if k == 'timing':
            t = TIMING_RE.match(m)
            if t:
                name, c, mean, mx = t.group(1), int(t.group(2)), int(t.group(3)), int(t.group(4))
                agg = s['timing'].setdefault(name, [0, 0, 0])
                agg[0] += c
                agg[1] += c * mean
                agg[2] = max(agg[2], mx)
                windows.setdefault((sid, (o.get('utc') or '')[:19]), {})[name] = (c, mean, mx, t.group(5))
        elif k == 'counters':
            for key, val in re.findall(r'(\w+)=(\d+)', m):
                if key not in ('window_ms', 'queue'):
                    s['counters'][key] += int(val)
        elif k == 'jvm':
            vals = dict(re.findall(r'(\w+)=(\d+)', m))
            for key in ('heap_used',):
                if key in vals:
                    s['jvm']['heap_used_peak'] = max(s['jvm'].get('heap_used_peak', 0), int(vals[key]))
            for key in ('heap_max', 'gc_count_total', 'gc_ms_total', 'system_ram_total', 'live_threads'):
                if key in vals:
                    s['jvm'][key] = int(vals[key])
        elif k == 'error':
            if len(s['errors']) < 12:
                s['errors'].append({'utc': o.get('utc'), 'segment': fn, 'message': m[:1500]})
        elif k == 'session_end':
            s['ends'].append(m)
        elif k == 'client':
            mm2 = re.search(r'in_world=(\w+)', m)
            if mm2:
                s['client_samples']['in_world_' + mm2.group(1)] += 1

    out = {'log_dir_files': sorted(os.path.basename(p) for p in glob.glob(os.path.join(log_dir, '*.log'))),
           'sessions': []}
    for sid, s in sessions.items():
        timing = {}
        for name, (c, tot, mx) in sorted(s['timing'].items()):
            if c:
                timing[name] = {'count': c, 'mean_us': tot // c, 'max_us': mx}
        act = [(k, w) for k, w in windows.items() if k[0] == sid and w.get('LOD_RENDER_WALL', (0,))[0] > 0]
        rest = [(k, w) for k, w in windows.items() if k[0] == sid and w.get('LOD_RENDER_WALL', (0,))[0] == 0
                and w.get('FRAME_INTERVAL', (0,))[0] > 0]

        def agg(ws):
            fr = sum(w['FRAME_INTERVAL'][0] for _, w in ws if 'FRAME_INTERVAL' in w)
            tot = sum(w['FRAME_INTERVAL'][0] * w['FRAME_INTERVAL'][1] for _, w in ws if 'FRAME_INTERVAL' in w)
            worst = max([w['FRAME_INTERVAL'][2] for _, w in ws if 'FRAME_INTERVAL' in w] or [0])
            return {'windows': len(ws), 'frames': fr,
                    'mean_frame_interval_ms': round(tot / fr / 1000.0, 2) if fr else None,
                    'mean_fps_equiv': round(1e6 / (tot / fr), 1) if fr and tot else None,
                    'worst_frame_interval_ms': round(worst / 1000.0, 1)}

        entry = {
            'session': sid, 'segments': sorted(s['segments']), 'first_utc': s['first_utc'], 'last_utc': s['last_utc'],
            'identity': s['identity'], 'mods': s['mods'], 'renderer_mode_timeline': s['modes'],
            'vbo_render_count_max': {'rendered': s['vbo_max'][0], 'candidates': s['vbo_max'][1]},
            'jvm': s['jvm'], 'timing_wall_totals': timing,
            'counters_nonzero_sum': {k: v for k, v in sorted(s['counters'].items(), key=lambda x: -x[1]) if v},
            'errors': s['errors'], 'session_end': s['ends'],
            'renderer_active_windows': agg(act),
            'renderer_active_window_detail': [
                {'utc': k[1], 'frames': w.get('FRAME_INTERVAL', (0, 0, 0, ''))[0],
                 'frame_mean_ms': round(w.get('FRAME_INTERVAL', (0, 0, 0, ''))[1] / 1000.0, 1),
                 'frame_max_ms': round(w.get('FRAME_INTERVAL', (0, 0, 0, ''))[2] / 1000.0, 1),
                 'lod_render_mean_ms': round(w['LOD_RENDER_WALL'][1] / 1000.0, 2),
                 'lod_render_max_ms': round(w['LOD_RENDER_WALL'][2] / 1000.0, 1),
                 'vbo': vbo.get(k)} for k, w in act],
            'other_windows': agg(rest),
        }
        out['sessions'].append(entry)
    return out


def to_markdown(summary):
    lines = ['# Resumo de sessoes Akashic Mirai (gerado)', '',
             'Arquivos lidos: ' + ', '.join(summary['log_dir_files']), '']
    for s in summary['sessions']:
        lines += ['## Sessao %s' % s['session'], '',
                  '- Janela: %s -> %s (UTC); segmentos: %s' % (s['first_utc'], s['last_utc'], ', '.join(s['segments'])),
                  '- Identidade: ' + json.dumps(s['identity'], ensure_ascii=False),
                  '- Modo do renderer: ' + json.dumps(s['renderer_mode_timeline']),
                  '- VBO Render Count (max): %(rendered)s/%(candidates)s' % s['vbo_render_count_max'],
                  '- JVM: ' + json.dumps(s['jvm']),
                  '- Janelas com renderer ativo: ' + json.dumps(s['renderer_active_windows']),
                  '- Demais janelas: ' + json.dumps(s['other_windows']),
                  '- Fim de sessao: ' + json.dumps(s['session_end']), '',
                  '| Timing (wall) | count | mean_us | max_us |', '|---|---:|---:|---:|']
        for name, t in s['timing_wall_totals'].items():
            lines.append('| %s | %d | %d | %d |' % (name, t['count'], t['mean_us'], t['max_us']))
        lines += ['', 'Contadores != 0: ' + json.dumps(s['counters_nonzero_sum'], ensure_ascii=False), '']
        for e in s['errors']:
            lines.append('- ERRO %s (%s): `%s`' % (e['utc'], e['segment'], e['message'][:400].replace('`', "'")))
        lines.append('')
    return '\n'.join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--log-dir', required=True)
    ap.add_argument('--out-json')
    ap.add_argument('--out-md')
    args = ap.parse_args(argv)
    summary = summarise(args.log_dir)
    if args.out_json:
        with open(args.out_json, 'w', encoding='utf-8') as f:
            json.dump(summary, f, indent=2, ensure_ascii=False)
    md = to_markdown(summary)
    if args.out_md:
        with open(args.out_md, 'w', encoding='utf-8') as f:
            f.write(md)
    if not args.out_json and not args.out_md:
        sys.stdout.write(md + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
