// Manual scenarios that cannot be automated. Each is recorded as pass / fail / n.a. plus notes.
export const MANUAL = [
  { id: 'restricted-folders', text: 'Try selecting: home folder, Desktop, Documents, Downloads, a drive root (C:\\ or /), a system folder, a removable drive. For each record whether the picker refuses it and with what message.' },
  { id: 'permission-revoked', text: 'After connecting, revoke "Edit files" in the site settings (lock icon). Run "Reconnect" test and an enumeration. Record the permission state and the error name thrown by reads and writes (expect NotAllowedError).' },
  { id: 'permission-persistence', text: 'Choose "Allow on every visit" if offered. Restart the browser and run "Reconnect". Record whether permission is already granted or prompt appears; confirm Reconnect needs a user click.' },
  { id: 'clear-site-data', text: 'Clear site data for the origin, reload, run "Reconnect". Expect: no stored handle (fresh non-destructive enrollment).' },
  { id: 'folder-renamed', text: 'While connected, rename the selected folder in the OS, then enumerate and write via the existing handle. Record whether the handle follows the folder, errors, or points at a new folder of the old name.' },
  { id: 'folder-replaced', text: 'Delete the selected folder and create a new one at the same path. Run "Reconnect" and isSameEntry against a fresh pick. Folder names alone must not prove identity.' },
  { id: 'root-disconnected', text: 'Start a perf scan on a large fixture and eject the removable drive / disconnect the network share mid-scan. Record the error type and that the scan reports failure rather than a clean (shorter) result.' },
  { id: 'tab-suspension', text: 'Start the lifecycle job; keep the tab in the background for 5 minutes (and with the window minimised, and across a laptop sleep). Record drift, freeze events, and whether the job continued or stopped.' },
  { id: 'browser-closure-resume', text: 'Close the browser mid-job, reopen, load the page. Job must be "interrupted" and must not run until Resume is clicked.' },
  { id: 'editor-save-behaviour', text: 'With the race injector in each mode (append / truncate-write / replace), also edit the race-target file with real editors (VS Code, IntelliJ, Notepad/TextEdit, vim) during a write. Record which editors fail or silently lose data.' },
  { id: 'windows-links', text: 'Windows: create file symlink (mklink), directory symlink (mklink /D), junction (mklink /J) and OneDrive placeholders in a test folder. Record how the harness "links" test sees them and what removal does.' },
  { id: 'hidden-system-files', text: 'Windows/macOS: hidden and system-attributed files, .DS_Store, Thumbs.db, desktop.ini. Record whether they are enumerated and readable.' },
  { id: 'antivirus-lock', text: 'Windows: with antivirus real-time scanning on, run the hashing and staged-write tests. Record sharing-violation errors or slowdowns.' },
  { id: 'long-paths', text: 'Windows: confirm behaviour beyond 260 characters, with and without LongPathsEnabled. The "entries" test records the depth reached.' },
  { id: 'edge-parity', text: 'Repeat the full automated suite and the 25,000-file / 1 GiB fixture run in Edge, not only Chrome.' },
];
