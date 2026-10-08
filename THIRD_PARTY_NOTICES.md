# Third-party notices

Current source (0.4.4):

- `whisper.cpp`, ggml-org, commit `6e4ab854f67f743900934a703d5603419384c961`: https://github.com/ggml-org/whisper.cpp — MIT. CPU-only saved-audio inference, including upstream ggml. Pinned source archive hash in `DEPENDENCIES.json`; complete upstream notice in `app/src/main/assets/licenses/whisper.cpp-MIT.txt`.
- OpenAI Whisper English model, quantized `ggml-small.en-q5_1.bin`, distributed by `ggerganov/whisper.cpp` at revision `5359861c739e955e79d9a303bcbc70fb988958b1`: https://huggingface.co/ggerganov/whisper.cpp — MIT. Bundled, hash-verified offline model. Original model notice in `app/src/main/assets/licenses/whisper-model-MIT.txt`.
- Vosk Android 0.3.75 and `vosk-model-small-en-us-0.15`: https://github.com/alphacep/vosk-api and https://alphacephei.com/vosk/models — Apache-2.0. Streaming live preview; pinned model/AAR hashes in `DEPENDENCIES.json`. Notice in `app/src/main/assets/licenses/vosk-license.txt`.
- JNA 5.18.1: https://github.com/java-native-access/jna — Apache-2.0 option. Vosk native binding; pinned AAR hash in `DEPENDENCIES.json`. Notice in `app/src/main/assets/licenses/jna-license.txt`.
- Concentus pure-Java Opus codec, lostromb/concentus commit `3885c4e46513ef0fc81fca100189e54f1714c6ca`: https://github.com/lostromb/concentus — BSD-style; full upstream notice in `app/src/main/assets/licenses/concentus-license.txt`.
- Omi BLE UUIDs and firmware protocol, BasedHardware/omi commit `d1fdcb4cc4fbd021eac799630e476f144e6bc18a`: https://github.com/BasedHardware/omi — MIT; notice in `app/src/main/assets/licenses/omi-license.txt`. Compatibility evidence in `verification/OMI-PROTOCOL.md`.
- Gradle wrapper: https://gradle.org — Apache-2.0. Android Gradle plugin from Google — Apache-2.0.
- Public JFK speech fixture `samples/jfk.wav`, from the pinned whisper.cpp source: test-only; provenance/hash in `verification/whisper-fixture.json`. Not included in the release APK.

License texts are accessible from Privacy and help → Licenses. This standalone app is not affiliated with Omi or Based Hardware. Owner-local Omi Private supplied selectively reused native build/model/adapter patterns; its BLE, PC/cloud/sync, credentials and key material were not copied for this migration.

Releases through 0.3.3 used Vosk alone. Release 0.4.0 replaced live recognition with Whisper and excluded Vosk/JNA. Release 0.4.1 restores the pinned Vosk/JNA runtime for streaming drafts and retains Whisper for post-save refinement. Public `test.wav` and `jfk.wav` fixtures are test-only, not shipped release recordings.
