# Maintaining the feature guide

GitHub Pages serves this directory from `main` at https://five0nit.github.io/NotTheOmiAIApp/ . It is plain HTML/CSS with local assets; no install step, framework, client-side JavaScript, analytics or external fonts.

## Files
- `index.html`: feature guide, version availability, setup, privacy, FAQ and metadata.
- `styles.css`: responsive layout, keyboard focus, print and reduced-motion styles.
- `favicon.svg`, `social.png`: original branding assets; no device screenshots or personal data.
- `.nojekyll`: serve files directly.
- `../tests/pages/check_site.py`: dependency-free content, link, asset, privacy and metadata checks.

## Edit and verify
1. Read the current public release and source version separately. Do not imply a development feature is in an older downloadable APK.
2. Verify features against the corresponding app code. Feature areas: MainActivity, LibraryBrowser, LibraryDates, Recordings, OmiSettingsActivity, ButtonActions, OmiCaptureService, OmiBle, NotificationVibrations and OmiNotificationListener. Later-build feature descriptions do not mean those source files are published at that version.
3. Update version badges, availability date, setup and metadata together. Link to an exact release: preview releases may not resolve via `/releases/latest`.
4. Run `python3 tests/pages/check_site.py` from the repository root.
5. Serve `docs/` on loopback, then verify desktop, tablet, 390px and 320px mobile layouts, keyboard navigation, the native FAQ disclosures, anchors and all outbound links. Check both document and text bounds for overflow. Recheck with JavaScript disabled; this site needs none.
6. Scan only the intended public changes for secrets, personal identifiers and private paths. Do not import device receipts or screenshots from a private development checkout.
7. Commit only documentation and its tests. Verify the Pages build and fetch the live HTML/CSS/images; compare their hashes with the commit before announcing publication.

## Release boundaries documented here
- Public source/APK: 0.4.7 / build14.
- Current separately verified build: 0.4.11 / build18.
- 0.4.8+: 15GB retained PCM limit and RF64 WAV export.
- 0.4.9: control-path refinements; do not claim every behaviour was newly introduced here. Current 0.4.11 includes expanded media actions and opt-in experimental triple-press (650ms combined-tap window), absent from public 0.4.7.
- Current UI includes a manual double-pulse test. Automatic notification events respect any active DND mode; the manual test checks the switch/access/connection but not DND itself.
- 0.4.10+: default-off notification haptics over the existing BLE connection.
- 0.4.11: nonterminal recovery for valid PCM-capacity exhaustion, not a fix for every BLE failure.

Never conflate static/host/emulator verification with physical wearable, locked-phone delivery or all-day endurance acceptance. Publishing this site does not publish a binary or install/change an app.
