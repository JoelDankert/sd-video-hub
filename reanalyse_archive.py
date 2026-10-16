#!/usr/bin/env python3
"""Refresh all completed archive cut lists without modifying media."""
import fcntl
import json
import sqlite3
import re
import time
from pathlib import Path
import app
from hub_config import load_config, video_policy


def main():
    lock = (app.DATA / 'reanalyse-archive.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    status = app.DATA / 'reanalyse-archive-status.json'
    log = (app.DATA / 'reanalyse-archive.log').open('a', buffering=1)
    with sqlite3.connect(app.DB) as connection:
        all_rows = connection.execute("SELECT files.id,files.output,sessions.label FROM files JOIN sessions ON sessions.id=files.session WHERE files.status='done' AND files.output IS NOT NULL ORDER BY files.id DESC").fetchall()
    settings=load_config()
    rows=[(fid,filename) for fid,filename,label in all_rows if video_policy(filename,label,settings)['enabled']]
    progress = {'started': time.time(), 'total': len(rows), 'finished': 0, 'errors': [], 'state': 'running'}
    def save():
        tmp = status.with_suffix('.tmp')
        tmp.write_text(json.dumps(progress, indent=2) + '\n')
        tmp.replace(status)
    save()
    for fid, filename in rows:
        progress['current'] = filename
        save()
        print(f"START {progress['finished']+1}/{len(rows)} {filename}", file=log, flush=True)
        app.analyse_flight(fid, Path(filename))
        with sqlite3.connect(app.DB) as connection:
            state, error = connection.execute('SELECT cut_status,error FROM files WHERE id=?', (fid,)).fetchone()
        if state != 'done':
            progress['errors'].append({'id': fid, 'error': error})
        progress['finished'] += 1
        save()
        print(f"FINISH {fid} {state}", file=log, flush=True)
    progress['state'] = 'finished'
    progress['ended'] = time.time()
    progress.pop('current', None)
    save()
    print(json.dumps(progress), flush=True)
    if progress['errors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
