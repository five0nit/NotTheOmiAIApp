#!/usr/bin/env python3
"""Dependency-free checks for the public GitHub Pages guide."""
from html.parser import HTMLParser
from html import unescape
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / "docs"
URL = "https://five0nit.github.io/NotTheOmiAIApp/"

class Document(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.ids, self.links, self.assets, self.meta = [], [], [], {}
        self.h1 = 0
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a: self.ids.append(a["id"])
        if tag == "a": self.links.append(a.get("href", ""))
        if tag == "h1": self.h1 += 1
        if tag == "img":
            assert "alt" in a
            self.assets.append(a["src"])
        if tag == "link" and a.get("rel") in ("stylesheet", "icon"):
            self.assets.append(a["href"])
        if tag == "meta": self.meta[a.get("name", a.get("property"))] = a.get("content")

class SiteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = (SITE / "index.html").read_text()
        cls.readme = (ROOT / "README.md").read_text()
        cls.text = unescape(re.sub(r'<[^>]+>', '', cls.html))
        cls.doc = Document(cls.html)
    def test_sections(self):
        self.assertEqual(self.doc.h1, 1)
        for section in ("overview", "how-it-works", "features", "library", "controls", "notifications", "privacy", "start", "versions", "faq", "developers"):
            self.assertIn(section, self.doc.ids)
        self.assertEqual(len(self.doc.ids), len(set(self.doc.ids)))
    def test_links_and_assets(self):
        for href in self.doc.links:
            self.assertTrue(href)
            if href.startswith("#"): self.assertIn(href[1:], self.doc.ids)
            elif not href.startswith("https://"): self.assertTrue((SITE / href).is_file(), href)
        for src in self.doc.assets:
            self.assertFalse(src.startswith(("http", "//")), src)
            self.assertTrue((SITE / src).is_file(), src)
    def test_metadata(self):
        for key in ("description", "viewport", "og:title", "og:type", "og:site_name", "og:description", "og:url", "og:image", "og:image:alt", "twitter:card", "twitter:title", "twitter:description", "twitter:image"):
            self.assertTrue(self.doc.meta.get(key), key)
        self.assertEqual(self.doc.meta["og:url"], URL)
        self.assertIn('rel="canonical" href="' + URL + '"', self.html)
    def test_version_separation(self):
        for document in (self.text, self.readme):
            for text in ("0.4.11 / build 18", "0.4.12 / versionCode 19", "No v0.4.12 APK release"):
                self.assertTrue(text in document, "Missing source/download distinction: " + text)
            for stale in ("source remains at", "source remains **0.4.7**", "0.4.7 source", "0.4.7 / build 14", "/releases/latest"):
                self.assertFalse(stale in document, "Stale source/download claim: " + stale)
        for document in (self.html, self.readme):
            self.assertNotRegex(document, r'/releases/(?:tag|download)/v0\.4\.12')
    def test_latest_download_and_android_only(self):
        apk = "https://github.com/five0nit/NotTheOmiAIApp/releases/download/v0.4.11/NotTheOmiAIApp-0.4.11-arm64-v8a.apk"
        self.assertGreaterEqual(self.doc.links.count(apk), 2)
        for text in ("Download v0.4.11 APK", "Android only for now", "no iOS version", "Android 8+", "arm64-v8a"):
            self.assertIn(text, self.html)
        for text in (apk, "Android only for now", "0.4.12 / versionCode 19", "0.4.11 / build 18"):
            self.assertTrue(text in self.readme, "Missing README release detail: " + text)
        downloads = [href for href in self.doc.links if "/releases/download/" in href]
        self.assertEqual(set(downloads), {apk})
    def test_complete_feature_topics(self):
        for text in ("Vosk", "Whisper", "small.en Q5_1", "AES-GCM", "Keystore", "RF64", "15,000,000,000", "128 MiB", "Notification vibrations", "No action", "Phone shortcut", "Calendar", "Search", "Rename", "Export text", "Export audio", "Delete recording", "Refine / retry with Whisper", "View original live draft", "Phone microphone", "120 seconds"):
            self.assertIn(text, self.html, text)
    def test_privacy_and_accessibility(self):
        for text in ('lang="en"', 'class="skip-link"', 'id="main"', "unencrypted", "uninstall", "No internet permission", "not a screenshot"):
            self.assertIn(text, self.html)
        self.assertNotRegex(self.html, r'<script(?![^>]*application/ld\+json)')
        self.assertNotRegex(self.html, r'(?:https?://)?(?:www\.)?(?:googletagmanager|google-analytics|fonts\.googleapis)')
        self.assertIn("prefers-reduced-motion", (SITE / "styles.css").read_text())
    def test_no_private_evidence(self):
        for file in [ROOT / "README.md", *SITE.rglob("*")]:
            if file.suffix not in (".html", ".css", ".md", ".svg", ".txt"): continue
            self.assertNotRegex(file.read_text(), r'/home/|/mnt/|gh[pousr]_[A-Za-z0-9]{20,}|BEGIN .*PRIVATE KEY|(?:chat_id|device_serial)\s*[:=]')
    def test_all_button_actions_and_experimental_gesture(self):
        html = unescape(self.html)
        for action in ("Bookmark in transcript", "Start recording", "Stop & save", "Start / stop recording", "Play / pause music", "Pause music", "Play music", "Next track", "Previous track", "Stop music", "Fast-forward music", "Rewind music", "Media volume up", "Media volume down", "Mute / unmute media", "Phone shortcut", "No action"):
            self.assertIn(action, html)
        for phrase in ("experimental combined taps", "650 ms", "Wearable shake is unavailable", "Included in 0.4.11"):
            self.assertIn(phrase, html)
    def test_source_profiles_and_gesture_windows(self):
        for document in (self.text, self.readme):
            for phrase in ("Everyday", "Meeting", "Music", "manually", "Single press", "Double press", "Triple press", "Four presses", "Five presses", "650 ms", "950 ms", "1250 ms", "Four/five start with No action", "fixed", "Shorter gestures wait", "shortcut targets", "No arbitrary macros"):
                self.assertTrue(phrase in document, "Missing profile/gesture contract: " + phrase)
        self.assertTrue("Switching profiles cancels pending gestures" in self.text)
        self.assertTrue("New in source 0.4.12" in self.text)
    def test_source_smart_pause_record_and_flashlight(self):
        for document in (self.text, self.readme):
            for phrase in ("Smart: bookmark recording / play-pause idle", "controls-only standby", "Pause music → start recording", "never stops an existing recording", "Flashlight toggle", "CameraManager", "Allow flashlight permission", "does not grant permission", "locked-screen", "physical acceptance"):
                self.assertTrue(phrase in document, "Missing action/acceptance contract: " + phrase)
        privacy = self.html.split('id="privacy"', 1)[1].split('</section>', 1)[0]
        self.assertTrue("Camera · source 0.4.12 only" in privacy)
    def test_source_only_labels_are_wrappable(self):
        # Keep the disclaimer outside the no-wrap tag, including at 320px.
        marker = '<span class="tag new">New in source 0.4.12</span> Not in the APK download.'
        for section in ("controls", "notifications"):
            content = self.html.split('id="' + section + '"', 1)[1].split('</section>', 1)[0]
            self.assertTrue(marker in content, "Missing source-only label: " + section)
    def test_source_notification_selection_and_patterns(self):
        for document in (self.text, self.readme):
            for phrase in ("All apps", "Selected apps only", "empty selected-app list", "no notification pulses", "Short pulse", "Double pulse", "Triple pulse", "Long pulse", "per-app", "opt-in", "phone-motor substitution"):
                self.assertTrue(phrase in document, "Missing notification contract: " + phrase)
        for phrase in ("Per-app patterns also apply in All apps mode", "notification settings are shared across profiles", "Test triple pulse on Omi"):
            self.assertTrue(phrase in self.text, "Missing notification guide detail: " + phrase)
    def test_retained_evidence_not_physical_certification(self):
        for document in (self.text, self.readme):
            for phrase in ("125 Android instrumentation tests", "54 gesture scenarios", "641 haptic/BLE scenarios", "overlap", "pending"):
                self.assertTrue(phrase in document, "Missing evidence boundary: " + phrase)
    def test_library_browsing_controls(self):
        for phrase in ("Today", "All dates", "12 per page", "Refresh saved recordings", "case-insensitive substring", "without a plaintext playback cache"):
            self.assertIn(phrase, self.html)
    def test_notification_filters_and_manual_test(self):
        for phrase in ("Test double pulse on Omi", "Do Not Disturb", "1.5 seconds", "manual test does not itself check DND", "off by default", "existing connection"):
            self.assertIn(phrase, self.html)
    def test_json_ld(self):
        values = re.findall(r'<script type="application/ld\+json">(.*?)</script>', self.html, re.S)
        self.assertEqual(len(values), 1)
        data = json.loads(values[0])
        self.assertEqual(data["name"], "NotTheOmiAIApp")
        self.assertEqual(data["softwareVersion"], "0.4.11")
        self.assertEqual(data["downloadUrl"], "https://github.com/five0nit/NotTheOmiAIApp/releases/download/v0.4.11/NotTheOmiAIApp-0.4.11-arm64-v8a.apk")

if __name__ == "__main__":
    unittest.main(verbosity=2)
