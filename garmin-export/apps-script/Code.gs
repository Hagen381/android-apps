/**
 * Garmin → Tabelle: nimmt die Daten der Android-App entgegen und schreibt sie in drei Reiter.
 * Vorhandene Zeilen (gleiche ID bzw. gleiches Datum) werden aktualisiert, nichts wird doppelt angelegt.
 *
 * Einrichtung: In der Google-Tabelle › Erweiterungen › Apps Script › diesen Code einfügen ›
 * Bereitstellen › Neue Bereitstellung › Typ „Web-App“ › Ausführen als: Ich › Zugriff: Jeder ›
 * Bereitstellen › Zugriff autorisieren › die Web-App-URL (…/exec) in die Android-App eintragen.
 */

const SHEETS = {
  Trainings: ['id', 'datum', 'start', 'ende', 'dauer_min', 'sportart', 'titel', 'puls_avg', 'puls_max', 'kcal', 'distanz_km', 'quelle'],
  Tage: ['datum', 'schritte', 'ruhepuls', 'kcal_gesamt', 'kcal_aktiv', 'distanz_km'],
  Schlaf: ['id', 'nacht', 'start', 'ende', 'dauer_h', 'tief_min', 'leicht_min', 'rem_min', 'wach_min', 'quelle'],
};

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const data = JSON.parse(e.postData.contents);
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    upsert_(ss, 'Trainings', data.sessions);
    upsert_(ss, 'Tage', data.days);
    upsert_(ss, 'Schlaf', data.sleep);
    sortByColumn_(ss, 'Trainings', 3);
    sortByColumn_(ss, 'Tage', 1);
    sortByColumn_(ss, 'Schlaf', 3);
    log_(ss, data);
    return json_({ ok: true });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function doGet() {
  return json_({ ok: true, info: 'Garmin → Tabelle bereit' });
}

function sheet_(ss, name) {
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.getRange(1, 1, 1, SHEETS[name].length).setValues([SHEETS[name]]).setFontWeight('bold');
    sh.setFrozenRows(1);
    sh.getRange('A:D').setNumberFormat('@'); // IDs, Daten und Zeiten als Text, damit nichts umgerechnet wird
  }
  return sh;
}

function upsert_(ss, name, rows) {
  if (!rows || !rows.length) return;
  const cols = SHEETS[name];
  const sh = sheet_(ss, name);
  const last = sh.getLastRow();
  const keys = last > 1 ? sh.getRange(2, 1, last - 1, 1).getDisplayValues().map(r => r[0]) : [];
  const index = new Map(keys.map((k, i) => [k, i + 2]));
  rows.forEach(r => {
    const values = cols.map(c => (r[c] === undefined || r[c] === null ? '' : r[c]));
    const key = String(r[cols[0]]);
    const at = index.get(key);
    if (at) {
      sh.getRange(at, 1, 1, cols.length).setValues([values]);
    } else {
      sh.appendRow(values);
      index.set(key, sh.getLastRow());
    }
  });
}

function sortByColumn_(ss, name, col) {
  const sh = ss.getSheetByName(name);
  if (sh && sh.getLastRow() > 2) {
    sh.getRange(2, 1, sh.getLastRow() - 1, sh.getLastColumn()).sort({ column: col, ascending: false });
  }
}

function log_(ss, data) {
  let sh = ss.getSheetByName('Protokoll');
  if (!sh) {
    sh = ss.insertSheet('Protokoll');
    sh.appendRow(['empfangen', 'gesendet', 'geraet', 'trainings', 'tage', 'naechte']);
    sh.getRange('A:B').setNumberFormat('@');
  }
  sh.appendRow([
    Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyy-MM-dd HH:mm'),
    data.gesendet || '', data.geraet || '',
    (data.sessions || []).length, (data.days || []).length, (data.sleep || []).length,
  ]);
  if (sh.getLastRow() > 500) sh.deleteRows(2, sh.getLastRow() - 500);
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
