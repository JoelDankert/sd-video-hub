# Downloads

Alle Download-Endpunkte verlangen dieselbe Basic Auth wie die Oberfläche. Das Archiv bleibt beim Download und bei der virtuellen Flugauswahl unverändert.

## Vollständige Archivdatei (Original-Download)

Der vollständige Download liefert die vorhandene **Archiv-MP4**, einschließlich Nicht-Flug-Abschnitten. „Original“ bedeutet hier ungeschnittene Archivdatei, **nicht** die ursprünglichen Recorderbytes: Beim Import wird normalerweise bereits verlustbehaftet nach H.264/AAC komprimiert. Der HTTP-Medienendpunkt unterstützt Range-Anfragen; `?download` setzt den Attachment-Header. Hier entsteht kein zusätzlicher temporärer Schnitt.

## Nur-Flug-Export

Die Oberfläche startet mit `POST /api/flight-download` und einer JSON-Video-ID einen Job; `GET /api/flight-download/<token>` liefert Status, nach `ready` steht `/flight-download/<token>` bereit. Voraussetzung sind eine abgeschlossene Archivdatei und eine freigegebene Fluganalyse. „Flug“ ist das Komplement der virtuellen Nicht-Flug-Intervalle, keine unabhängig bestätigte Flugmessung.

ffmpeg trimmt und verkettet die beibehaltenen Video-/Audioabschnitte und **kodiert neu**, kein verlustfreies Stream-Copy:

- Video: `libx264`, `yuv420p`, High-Profil, Level `4.0`, `+faststart`, entfernte Metadaten.
- Defaults: `-preset veryfast -crf 20 -threads 2`.
- Audio, falls vorhanden: AAC `128k`, angepasste Zeitstempel und Längen; Videos ohne Ton bleiben ohne Ton.
- Exportdauer wird mit ffprobe geprüft (maximal eine Sekunde Abweichung zur erwarteten Dauer).

Die Datei entsteht ausschließlich temporär unter `SDH_DATA/flight-downloads/`. **Archivvideo und Archiv-Cutlist werden beim Export nicht verändert.** Das gilt auch beim Fehlschlag des Exports.

## Lebenszyklus und Grenzen

Statusfolge: `queued` → `rendering` → `ready` → `delivering` → `delivered`; Fehler ergeben `error`. Maximal `3` offene Jobs, nur ein Encoder gleichzeitig; ein bereits laufender/bereiter Job desselben Videos wird wiederverwendet. Konfiguration und Validierung: [CONFIG.md](CONFIG.md).

- Erfolgreiche serverseitige Übertragung: temporäre Datei sofort entfernt, Status `delivered`.
- **Abbruch während der Übertragung:** Datei bleibt zunächst erhalten, Status zurück auf `ready`, damit erneut heruntergeladen werden kann. Kein sofortiges Löschen bei jedem Client-Abbruch.
- Renderfehler oder Encoder-Timeout: temporäres Jobverzeichnis wird entfernt.
- TTL: Standard `3600` Sekunden; Cleanup ungefähr minütlich und bei Jobstarts. Inaktive Jobs und verwaiste Verzeichnisse verfallen; aktiv wartende, rendernde oder ausliefernde Jobs sind von der normalen Job-TTL ausgenommen. Nach einem Prozessneustart werden alte Verzeichnisse erst nach ihrem Ablauf aufgeräumt.

`delivered` bedeutet nur: Der Server konnte alle Bytes schreiben und flushen. **Es beweist nicht, dass der Browser die Datei dauerhaft gespeichert hat.** Nach erfolgreicher Auslieferung ist der Token nicht erneut abholbar; einen neuen Export anfordern. Temporäre Nur-Flug-Downloads unterstützen kein HTTP-Range/Resume.

Viel freier Speicher ist nötig; die Größenprüfung vor dem Rendern ist nur eine Schätzung. Langsamere Presets können auf dem Pi lange dauern. Die Parameter `temporary_ttl_seconds`, `max_pending`, `encode_timeout_seconds`, `preset`, `crf` und `threads` steuern Exporte, nicht die ursprüngliche Archivkompression. Zeitlimit und TTL sind getrennte Mechanismen.
