#!/usr/bin/env python3
"""Dependency-free checks for the public GitHub Pages guide."""
from html.parser import HTMLParser
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
        for text in ("0.4.11", "0.4.7", "not included in the public 0.4.7 APK", "/releases/tag/v0.4.7"):
            self.assertIn(text, self.html)
        self.assertNotIn("/releases/latest", self.html)
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
        for file in SITE.rglob("*"):
            if file.suffix not in (".html", ".css", ".md", ".svg", ".txt"): continue
            self.assertNotRegex(file.read_text(), r'/home/|/mnt/|gh[pousr]_[A-Za-z0-9]{20,}|BEGIN .*PRIVATE KEY|(?:chat_id|device_serial)\s*[:=]')
    def test_all_button_actions_and_experimental_gesture(self):
        from html import unescape
        html = unescape(self.html)
        for action in ("Bookmark in transcript", "Start recording", "Stop & save", "Start / stop recording", "Play / pause music", "Pause music", "Play music", "Next track", "Previous track", "Stop music", "Fast-forward music", "Rewind music", "Media volume up", "Media volume down", "Mute / unmute media", "Phone shortcut", "No action"):
            self.assertIn(action, html)
        for phrase in ("experimental combined taps", "650 ms", "Wearable shake is unavailable", "not in public APK"):
            self.assertIn(phrase, html)
    def test_library_browsing_controls(self):
        for phrase in ("Today", "All dates", "12 per page", "Refresh saved recordings", "case-insensitive substring", "without a plaintext playback cache"):
            self.assertIn(phrase, self.html)
    def test_notification_filters_and_manual_test(self):
        for phrase in ("Test double pulse on Omi", "Do Not Disturb", "1.5 seconds", "manual test does not itself check DND", "off by default", "existing connection"):
            self.assertIn(phrase, self.html)
    def test_json_ld(self):
        values = re.findall(r'<script type="application/ld\+json">(.*?)</script>', self.html, re.S)
        self.assertEqual(len(values), 1)
        self.assertEqual(json.loads(values[0])["name"], "NotTheOmiAIApp")

if __name__ == "__main__":
    unittest.main(verbosity=2)
