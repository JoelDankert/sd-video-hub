# Flugerkennung und Neuanalyse

Die Erkennung ist eine **OSD-basierte Schnitt-Policy, keine physikalische Flugmessung**. `DISARMED`, `MENU` und `STATS` markieren Nicht-Flug. Unbekannte oder nicht bestätigte Frames gelten nach Policy als Flug: Das beweist weder Bewegung noch einen scharfgeschalteten Copter. Fehlende OSD-Texte, Störungen und andere Schriften können die Schnittliste verfälschen.

## Ein Klassifikator, alle Schriftbänke

`hud_templates.py` dekodiert mit ffmpeg alle **2 Sekunden** ein Graustufenbild in nativer Auflösung. `osd_matcher.py` verwendet für alle aktivierten MAX7456-Schriftbänke denselben `coarse_to_fine_v2`-Klassifikator:

1. **Positionsunabhängige Ganzbildsuche:** Auf `480 × 270` verkleinertes Bild, Hochpass/helle Schriftanteile und Template-Korrelation liefern Kandidaten aus dem gesamten Frame. Keine feste Ecke oder fest definierte Textposition.
2. **Inferenz fester Rasterfamilien:** Aus Seitenverhältnis und Auflösung werden analoge PAL-/NTSC- bzw. digitale HD-OSD-Raster abgeleitet. Analog: 30 Spalten und aus nativer Breite abgeleitete Zellhöhe; digital: 40 Spalten und 16 Zeilen. Benachbarte gerundete Höhen werden geprüft. Das ist keine beliebige freie Skalen-/Schriftgrößensuche.
3. **Native Verifikation:** Um Kandidaten herum werden im Originalbild die weißen **und schwarzen** Schriftanteile maskiert korreliert. Zusätzlich müssen die einzelnen Buchstaben konsistent passen; ein hoher Gesamtscore allein genügt nicht.
4. **Schrifthinweis, keine Sonderpipeline:** Quellregel/Standardschrift und zuletzt erfolgreiche Schrift priorisieren die Bankreihenfolge. Alle aktivierten Bänke bleiben Kandidaten; keine eigene Erkennung pro Quellgruppe.
5. **Zeitliche Stützung:** Akzeptierte Wörter werden über nahe Zeitpunkte und ungefähr dieselbe Position gestützt. Diese Positionsähnlichkeit zwischen Treffern ist **keine feste Suchposition** im Bild.

## Aktuelle Parameter

Werte aus `config.example.json`:

| Parameter | Default | Verwendung |
| --- | --- | --- |
| `sample_seconds` | `2` | Frame-Abstand; akzeptierter Treffer deckt ungefähr ±1 Sekunde ab |
| `strong_score`, `weak_score` | `0.78`, `0.70` | Starker Anker / schwächerer zeitlich gestützter Treffer |
| `single_hit_score` | `0.78` | Ein buchstabenverifizierter starker Einzeltreffer kann direkt Anker sein |
| `confirmation_seconds` | `4` | Nachbarschaft zur alternativen Bestätigung eines starken Ankers |
| `support_seconds` | `15` | Zeitfenster für schwache Treffer um bestätigte Anker |
| `position_tolerance_pixels` | `5` | Maximale Koordinatenabweichung im 480×270-Suchbild; Wort und Schrift müssen übereinstimmen |
| `candidate_floor`, `candidate_peaks` | `0.40`, `8` | Coarse-Mindestscore und maximale Peaks je Template |
| `letter_min_score` | `0.15` | Jeder Buchstabe muss strikt darüber liegen |
| `letter_good_score`, `letter_good_fraction` | `0.45`, `0.75` | Mindestens aufgerundet 75 % der Buchstaben erreichen 0.45 |
| `letter_mean_score` | `0.65` | Mindestmittelwert der Buchstabenscores |
| `min_flight_seconds` | `5` | Fluglücken **unter 5 Sekunden** werden zu virtuellem Nicht-Flug geglättet, auch an den Rändern |

Da `single_hit_score` aktuell gleich `strong_score` ist, braucht ein starker, buchstabenverifizierter Treffer keinen zweiten Frame zur Ankerbestätigung. Schwächere Treffer benötigen weiterhin zeitliche Stützung. Der Server glättet kurze Fluglücken zusätzlich und kann einen gegenüber der Containerdauer kürzeren Videostream als Nicht-Flug-Endstück berücksichtigen.

`vertical_scales: [0.8, 0.9]`, `analog_horizontal_scale: 1.3333333333333333` und `digital_horizontal_scale: 1.0` sind noch Konfigurationsfelder, werden vom aktuellen Matcher aber **nicht für eine freie Skalenvariation verwendet**. Maßgeblich sind die im Matcher inferierten Rasterfamilien.

## Ergebnisse und Grenzen

- `library/.flight-skip.json`: virtuelle Nicht-Flug-Intervalle und Analyse-/Policy-Metadaten; keine geschnittenen Archivvideos.
- `template-reports/`: Detailberichte mit Samples, Scores, Buchstabenprüfung und akzeptierten Treffern.
- `state.sqlite3`: Analysefortschritt, `cut_status`, Fehler und Archivzuordnung.

Historische stichprobenartige Audits an realen Frames ergaben **93/93** passend bewertete Samples. Das ist weder eine allgemeine Genauigkeitsangabe noch eine Garantie für andere Aufnahmen; die Auditbilder sind nicht mitgeliefert. Keine vollständige automatisierte Testsuite enthalten. Für diesen Snapshot wurden keine neuen Tests ausgeführt.

## Manuell neu analysieren

Nur gegen ein **bereits initialisiertes Archiv** mit passender `state.sqlite3`, vorhandenen Archivdateien und vorbereiteter `.venv-template` verwenden. Das ist keine Erstinitialisierung und kein eigenständiger Medienimport. Dasselbe `SDH_DATA` wie beim Server wählen, aus dem Projektverzeichnis arbeiten und parallele Wartung/Analyse möglichst vermeiden. Die Befehle unten sind Dokumentation und wurden hier nicht ausgeführt.

Alle abgeschlossenen, laut Konfiguration freigegebenen Archivvideos:

```sh
python3 reanalyse_archive.py
```

Die Routine verwendet `reanalyse-archive.lock`; Fortschritt und Fehler stehen unter der Datenwurzel in `reanalyse-archive-status.json` und `reanalyse-archive.log`. Fehler einzelner Analysen führen nach dem Durchlauf zu einem Fehler-Exitstatus.

Genau einen vorhandenen Eintrag auswählen, entweder über interne ID oder eindeutigen Archivdateinamen:

```sh
python3 refresh_one_template.py "$VIDEO_ID"
# alternativ:
python3 refresh_one_template.py "$ARCHIVE_FILENAME"
```

Diese Variablen vorher lokal setzen; der Name muss genau einen abgeschlossenen Eintrag finden. Die Einzelroutine aktualisiert DB, Bericht und Cutlist und gibt das Ergebnis auf stdout aus; sie besitzt keinen eigenen Sammelstatus wie die Archivroutine. Beide Wege ändern Metadaten, **nicht die Medien**. Der Server-Watcher wird durch diese Wartungsskripte nicht gestartet.
