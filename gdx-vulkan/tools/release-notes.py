#!/usr/bin/env python3
"""Drafts release notes from what the repository records: the checklist counts, the commits since the last tag, and the known limitations in the README.

    python tools/release-notes.py [previous tag]  > notes.md

It writes a draft to be edited by hand: it cannot know what was run on which device for this commit (docs/RELEASE.md lists the runs a release needs).
"""
import re
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
checklist = (root / 'docs' / 'CHECKLIST.md').read_text(encoding='utf-8')
readme = (root / 'README.md').read_text(encoding='utf-8')

done = len(re.findall(r'^- \[x\]', checklist, re.M))
partial = len(re.findall(r'^- \[~\]', checklist, re.M))
todo = len(re.findall(r'^- \[ \]', checklist, re.M))

prev = sys.argv[1] if len(sys.argv) > 1 else None
rng = f'{prev}..HEAD' if prev else 'HEAD'
log = subprocess.run(['git', 'log', '--no-merges', '--format=- %s (%h)', rng, '--', str(root)], capture_output=True, text=True, cwd=root).stdout
head = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], capture_output=True, text=True, cwd=root).stdout.strip()

limits = re.search(r'## Known limitations\n(.*?)\n## ', readme, re.S)
print(f'# gdx-vulkan <version> (commit {head})\n')
print(f'Checklist: {done} items done, {partial} partial, {todo} not started (docs/CHECKLIST.md).\n')
print('## What changed\n')
print(log.strip() or '- (no commits)')
print('\n## What was run, where (fill in from docs/RELEASE.md)\n')
print('| Run | Device / driver | Result |')
print('|---|---|---|')
print('| `gradlew check`, both tiers | | |')
print('| device suite, both tiers | | |')
print('| soak, 10 minutes | | |')
print('| demos by hand | | |')
print('\n## Measured against stock libGDX\n')
print('See "Demo measurements" in the README for the tables (including the cases gdx-vulkan does not win).')
print('\n## Known limitations\n')
print(limits.group(1).strip() if limits else '(see README)')
