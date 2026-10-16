"""Shared, read-only configuration for the service, player and detector."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CONFIG_PATH = ROOT / 'config.json'


def load_config():
    settings = json.loads(CONFIG_PATH.read_text())
    if settings.get('version') != 1:
        raise ValueError('Unsupported config.json version')
    for key in ('sound_enabled', 'skip_nonflight', 'crop_4_3'):
        if type(settings['playback'][key]) is not bool:
            raise ValueError(f'playback.{key} must be boolean')
    muted = settings['playback'].get('muted_video_ids', [])
    if not isinstance(muted, list) or any(type(ident) is not int or ident <= 0 for ident in muted):
        raise ValueError('playback.muted_video_ids must list positive video IDs')
    if settings['archive']['sort_by'] not in ('recorded_at', 'id', 'name') or settings['archive']['sort_direction'] not in ('asc', 'desc'):
        raise ValueError('Invalid archive sort order')
    downloads = settings['downloads']
    for key in ('temporary_ttl_seconds', 'max_pending', 'encode_timeout_seconds', 'threads'):
        if type(downloads[key]) is not int or downloads[key] <= 0:
            raise ValueError(f'downloads.{key} must be a positive integer')
    if downloads['preset'] not in ('ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium', 'slow') or not 0 <= downloads['crf'] <= 51:
        raise ValueError('Invalid download encoding options')
    detection = settings['detection']
    if type(detection['enabled']) is not bool:
        raise ValueError('detection.enabled must be boolean')
    if not 0 < detection['weak_score'] <= detection['strong_score'] <= 1:
        raise ValueError('Invalid detection thresholds')
    for key in ('sample_seconds', 'confirmation_seconds', 'support_seconds', 'position_tolerance_pixels', 'min_flight_seconds', 'frame_width', 'frame_height'):
        if not isinstance(detection[key], (int, float)) or detection[key] <= 0:
            raise ValueError(f'detection.{key} must be positive')
    if detection.get('classifier') != 'coarse_to_fine_v2':
        raise ValueError('Unsupported detection classifier')
    if type(detection.get('candidate_peaks')) is not int or not 1 <= detection['candidate_peaks'] <= 16:
        raise ValueError('Invalid candidate peak limit')
    for key in ('candidate_floor', 'single_hit_score', 'letter_min_score', 'letter_good_score', 'letter_good_fraction', 'letter_mean_score'):
        if not isinstance(detection.get(key), (int, float)) or not 0 <= detection[key] <= 1:
            raise ValueError(f'Invalid detection.{key}')
    for key in ('frame_width', 'frame_height'):
        if type(detection[key]) is not int:
            raise ValueError(f'detection.{key} must be an integer')
    if not detection['words'] or any(not re.fullmatch('[A-Z]+', word) for word in detection['words']):
        raise ValueError('Detection words must be uppercase ASCII')
    for name, font in settings['fonts'].items():
        if type(font['detection_enabled']) is not bool:
            raise ValueError(f'fonts.{name}.detection_enabled must be boolean')
        path = (ROOT / font['file']).resolve()
        if not path.is_relative_to(ROOT / 'assets') or not path.is_file():
            raise ValueError(f'Invalid font asset: {name}')
    if detection['default_font'] not in settings['fonts']:
        raise ValueError('Unknown default font')
    for rule in settings['video_rules']:
        if rule['font'] != 'auto' and rule['font'] not in settings['fonts'] or type(rule['detection_enabled']) is not bool:
            raise ValueError('Invalid video rule')
        if not set(rule['match']) <= {'session_label', 'path_contains', 'pict_max'}:
            raise ValueError('Unknown video rule predicate')
    return settings


def video_policy(path, session_label='', settings=None):
    settings = load_config() if settings is None else settings
    path = Path(path)
    pict = re.fullmatch(r'PICT0*(\d+)', path.stem, re.I)
    font_name = settings['detection']['default_font']
    enabled = True
    rule_name = 'default'
    for rule in settings['video_rules']:
        match = rule['match']
        # Session label and path substring identify the same source by either handle.
        source = ('session_label' not in match and 'path_contains' not in match) or (
            match.get('session_label') == session_label or
            bool(match.get('path_contains') and match['path_contains'].lower() in str(path).lower()))
        number = 'pict_max' not in match or (pict and int(pict.group(1)) <= match['pict_max'])
        if source and number:
            font_name, enabled, rule_name = rule['font'], rule['detection_enabled'], rule['name']
            break
    font_enabled = any(font['detection_enabled'] for font in settings['fonts'].values()) if font_name == 'auto' else settings['fonts'][font_name]['detection_enabled']
    enabled = bool(enabled and settings['detection']['enabled'] and font_enabled)
    return {'font': font_name, 'enabled': enabled, 'rule': rule_name}
