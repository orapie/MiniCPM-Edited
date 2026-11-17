# MiniCPM-Edited

[简体中文](README.zh-CN.md)

Run local GGUF models on Android for standard chat, retrieval-augmented generation (RAG), and character chat. The app includes a knowledge pack for *The Dark Forest*, the second novel in the *Three-Body* series. In character mode, you can chat with Luo Ji, Shi Qiang, Zhang Beihai, Ding Yi, or Zhuang Yan and choose how far into the story they can draw on events. Supported MiniCPM-V models accept images, MiniCPM-V 4.6 supports video understanding, and VoxCPM2 has a dedicated text-to-speech (TTS) page.

## Build and Run

Open the repository root in Android Studio. The build requires Android SDK 36.1, NDK `27.0.12077973`, CMake `4.1.2`, and Python 3. The app version is `2.4` (`versionCode = 16`). It requires Android 7.0 (API 24) or later and targets API 36. The APK contains native libraries for `arm64-v8a`, so run it on an ARM64 device or emulator.

```bash
python3 scripts/prepare_database_bundle.py --check
bash ./gradlew :app:assembleDebug :app:verifyDebugDatabaseAssets
```

The debug APK is at `app/build/outputs/apk/debug/MiniCPM-Edited-debug.apk`. After installation, the app appears as **MiniCPM** in the Android launcher. Download and select a chat model on the Model Management page. You can download GGUF weights to the device or use an existing local model directory. Vision and TTS models also require their respective companion files. For available models and their capabilities, see the [chat model registry](app/src/main/java/com/example/minicpm_v_demo/ModelInfo.kt).

To use Dual Agent mode, first select a text-only GGUF model for Agent A on the Model Management page and make sure its files are available. Then enable Dual Agent mode. It is off by default and uses additional device memory. See the [Agent A model registry](app/src/main/java/com/example/minicpm_v_demo/harness/attribution/AttributionModelRegistry.kt) for model choices.

## Package a Release

In Android Studio, select **Build → Generate Signed Bundle / APK** and use the signing key from the previous release to build a signed release APK. Upload `MiniCPM-Edited-release.apk` to GitHub Releases. You can use `MiniCPM-Edited v2.4` as the release title. If you choose Android App Bundle, the output file is `MiniCPM-Edited-release.aab`.

## Chat Modes

| Mode | How it works |
|---|---|
| Standard Chat (`OFF`) | The chat model processes the user's input directly. |
| `RAG` | Vector search retrieves relevant passages from the active knowledge pack and adds them to the chat model's `user` message. |
| Character + RAG (`CHARACTER_RAG`) | The app builds a prompt with the selected character's identity, voice, and publicly known experiences up to the story cutoff. It filters external references by character and story cutoff before passing them to the response pipeline. |

The bundled knowledge pack uses a BGE ONNX query encoder and a 512-dimensional BGE vector index. The 384-dimensional hash n-gram index supports older knowledge pack formats.

In character mode, Agent A labels reference passages with character attributions. Its `user` message contains JSON, and it returns a constrained JSON array of attributions. After Kotlin validates the output, it formats the original passages and their labels as text by default and passes them to Agent B. Debug builds let you compare this with a JSON-formatted `user` message for Agent B. If Agent A is disabled or fails, Agent B uses the two messages that were prepared before attribution. When a user asks one question about the current character and a single public fact on the character card answers it, Agent B uses that fact and skips external references and Agent A.

## Project Structure

| Path | Contents |
|---|---|
| [app/](app/) | Android UI, harness orchestration, Dual Agent mode, chat sessions, and JNI. |
| [llama.cpp-omni/](llama.cpp-omni/) | Native code for local inference and multimodal processing. |
| [database/](database/) | The sole build input for the runtime knowledge pack. It contains character cards, story events, reviewed facts, and indexes. |
| [three_body_2_kb_builder/](three_body_2_kb_builder/) | Offline tools for building character cards, a SQLite knowledge base, and semantic indexes from the novel text. |
| [scripts/](scripts/) | Knowledge pack generation, validation, and APK checks. |

The build copies the files listed in the manifest to `app/build/generated/databaseAssets/database/` and packages them as `assets/database/`. On startup, the app verifies the files and installs them to `<filesDir>/database/` on the device. Character prompt templates are packaged separately as app resources. To update the knowledge pack, start with `database/` at the repository root. See the [knowledge pack maintenance guide](database/README.md).

## Tests and Documentation

```bash
bash ./gradlew :app:testDebugUnitTest
```

- [RAG, Dual Agent mode, and log troubleshooting](docs/RAG/RAG_Logic.md)
- [Follow-up plan for character fidelity and Dual Agent mode](docs/double_agent/Agent_B_JSON_Prompt_Experiment_Plan.md)
- [Roleplay regression results and commands for rerunning tests on a device](docs/double_agent/roleplay_eval/results.md)
