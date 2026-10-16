# Konfiguration

`config.example.json` nach `config.json` kopieren; nur die Beispieldatei gehört ins Repository. Die Defaults sind anonymisiert: lokale stummgeschaltete Video-IDs und quellspezifische Root-/Pfadregeln wurden bewusst entfernt (`muted_video_ids: []`, `video_rules: []`). JSON-Version ist `1`.

`hub_config.py` liest die Konfiguration bei vielen Operationen neu. Änderungen starten keine automatische Neuanalyse bestehender Cutlists; laufende Downloadaufträge behalten ihre beim Start übernommenen Parameter. Bestehende Analysen bei Bedarf gezielt erneuern, siehe [DETECTION.md](DETECTION.md).

## Umgebungsvariablen

| Variable | Bedeutung |
| --- | --- |
| `SDH_PASSWORD` | Pflicht für den Serverstart; nur lokal per Umgebung oder geschützter EnvironmentFile setzen, niemals committen |
| `SDH_DATA` | Datenwurzel; standardmäßig `data/` neben `app.py`. Relative eigene Werte beziehen sich auf das Arbeitsverzeichnis |

Unter der Datenwurzel entstehen `state.sqlite3`, `library/.flight-skip.json`, `staging/`, `thumbs/`, `template-reports/`, temporäre Downloads und Wartungsstatus. Für ein eigenes `SDH_DATA` einen **absoluten Pfad** verwenden: Relative Überschreibungen sind zwar zulässig, können aber mit der vorhandenen Medien-URL-/Pfadauflösung kollidieren. Nicht nur die Videos, sondern **die gesamte Datenwurzel** privat halten und sichern. Der Python-Pfad des Detektors ist unabhängig davon fest `.venv-template/bin/python` im Projekt.

## Player und Archiv

- `playback.sound_enabled: true`, `skip_nonflight: true`, `crop_4_3: false`: Vorgaben für Ton, virtuelles Überspringen und Crop.
- `playback.muted_video_ids: []`: optional positive interne Video-IDs, für die Ton abgeschaltet wird. Keine portablen Dateibezeichner.
- `archive.sort_by: "id"`, `sort_direction: "desc"`: gültige Sortierfelder `id`, `recorded_at`, `name`; Richtung `asc`/`desc`.
- `archive.group_order: ["hdzero", "pict_new", "pict_old"]`: konfigurierte Gruppenreihenfolge. Die Oberfläche enthält zusätzlich eigene Gruppierungs-/Sortierlogik; nicht jede UI-Eigenschaft ist allein über diese Felder steuerbar.

## Detektor

Alle Werte der Beispieldatei und ihre tatsächliche Verwendung stehen in [DETECTION.md](DETECTION.md). `detection.enabled` ist der globale Schalter, `default_font: "impact_mini"` ein Quellhinweis, keine Beschränkung der Suche auf genau diese Schrift.

Unter `fonts` stehen `file`, `detection_enabled` und die Herkunfts-URL `source`. Dateien müssen innerhalb von `assets/` liegen und vorhanden sein. Aktuell sind `impact_mini`, `clarity` und `default` aktiviert. `source` dient der Dokumentation; die Analyse lädt die lokalen `.mcm`-Dateien und holt nichts automatisch aus dem Netz.

## Video-Regeln

`video_rules` wird der Reihe nach geprüft; **die erste passende Regel gewinnt**. Eine Regel enthält `name`, `match`, `font` (bekannter Schriftname oder `auto`) und `detection_enabled` (Boolean).

Generisches Strukturbeispiel, nicht Bestandteil der Defaults:

```json
{
  "name": "source-policy",
  "match": {
    "session_label": "CARD",
    "path_contains": "source-folder",
    "pict_max": 100
  },
  "font": "auto",
  "detection_enabled": true
}
```

- `session_label` (exakter Vergleich) **ODER** `path_contains` (Teilstring ohne Beachtung der Groß-/Kleinschreibung) identifizieren eine Quelle. Beide müssen nicht gleichzeitig zutreffen. Ohne beide Prädikate passt jede Quelle.
- Ein optionales `pict_max` gilt **zusätzlich**: Der Dateistamm muss `PICT` plus Ziffern entsprechen und seine Nummer darf den Grenzwert nicht überschreiten. Ohne passende PICT-Nummer greift diese Regel nicht.
- Ohne Regel gelten Standardschrift und aktivierte Erkennung. Der globale Schalter und die Schriftfreigaben bleiben wirksam: Bei `auto` muss mindestens eine Schrift aktiviert sein, bei benannter Schrift diese selbst.
- Bei aktiver Analyse prüft derselbe Klassifikator trotzdem alle aktivierten Schriftbänke; `font` beeinflusst die Priorisierung, nicht die räumliche Suche.

## Downloadparameter

| `downloads`-Feld | Default | Zweck |
| --- | --- | --- |
| `temporary_ttl_seconds` | `3600` | Ablaufzeit inaktiver/abgeschlossener Jobs und verwaister temporärer Verzeichnisse |
| `max_pending` | `3` | Grenze gleichzeitig offener Jobs einschließlich bereiter/ausliefernder Downloads |
| `encode_timeout_seconds` | `3600` | Zeitlimit pro ffmpeg-Export |
| `preset` | `veryfast` | x264-Geschwindigkeits-/Kompressionsabwägung |
| `crf` | `20` | Qualitätsparameter, kleiner bedeutet höhere Qualität/größere Dateien |
| `threads` | `2` | x264-Threads |

Positive Ganzzahlen sind für Zeitlimits, Jobgrenze und Threads erforderlich. Erlaubte Presets: `ultrafast`, `superfast`, `veryfast`, `faster`, `fast`, `medium`, `slow`; CRF: `0` bis `51`. Diese Einstellungen betreffen **temporäre Exporte**, nicht die Archivkompression (im Code: x264 `medium`, CRF `28`, AAC `128k`).

Es gibt keinen konfigurierbaren Nur-Lesen-/Trockenlaufmodus für den Karten-Watcher. Auch deaktivierte Erkennung verhindert die automatische Quellenlöschung nicht.
