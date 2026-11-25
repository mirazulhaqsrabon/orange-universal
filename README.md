# Universal Language Manager

A Xed-Editor extension that detects the active file type and installs TextMate syntax packs from a GitHub-hosted registry, with bundled fallback assets for offline recovery.

## Language catalog

ULM uses the remote registry at `language-packs/languages.json` on this repository's `feature/detect-install-syntax` branch. Grammar and configuration files are downloaded from `language-packs/<id>/`. The bundled `app/src/main/assets/languages.json` and files remain as an offline fallback.

Current bundled language:

- **Astro** — `.astro` files, using the Astro TextMate grammar.

## Asset layout

```text
app/src/main/assets/
├── languages.json
├── astro.tmLanguage.json
└── Astro.config.json
```

The runtime fetches the online catalog over HTTPS, downloads the matching grammar/configuration into a staging directory, validates the grammar scope, then installs it into Xed's private ULM data directory. If an asset download fails, the matching bundled asset is used as a fallback. The active TextMate registry and matching Xed `FileType` are then registered.

## Add another language

1. Put its TextMate grammar directly in `app/src/main/assets/`.
2. Put its language configuration there too, if it has one.
3. Add an entry to `languages.json`:

```json
{
  "id": "python",
  "name": "Python",
  "version": "1.0.0",
  "extensions": ["py", "pyw"],
  "scopeName": "source.python",
  "grammar": "python.tmLanguage.json",
  "languageConfiguration": "Python.config.json"
}
```

4. Ensure `scopeName` matches the grammar file's `scopeName`.
5. Build the extension. The new language will appear in `ulm search`.

The `id` must be unique. `grammar` and `languageConfiguration` must be root-level asset filenames. Each language can list one or more file extensions.

## Install the extension

1. Download the `.xed` package from a successful GitHub Actions build artifact.
2. In Xed-Editor, open **Settings → Store → Install from storage**.
3. Select the downloaded `.xed` file.
4. Open the Xed Ubuntu terminal and run:

```sh
ulm help
```

The launcher is installed in Xed's own shared terminal environment; it does not modify unrelated Android or Ubuntu installations.

## Orange editor menu

The editor's three-dot overflow menu includes **Orange**. Selecting it opens an Orange command palette with **Universal Language Manager** and **Detect & Download Syntax** actions. The entry is registered through Xed's editor-toolbar command API and is removed from the toolbar action list when the extension is disposed. On narrow screens, Xed places the overflow actions in the three-dot menu.

## Active-file syntax detection

The **Detect & Download Syntax** action reads the active file's extension and installs or enables the matching language pack from the online catalog. The default shortcut is **Ctrl+Shift+L**.

The online catalog currently contains Astro only, so `.astro` is the only supported remote pack. Add more packs under `language-packs/<id>/` and register them in `language-packs/languages.json`.

## Commands

| Command | Description |
| --- | --- |
| `ulm help` | Show command help |
| `ulm search <query>` | Search bundled languages |
| `ulm install <language-id>` | Install a bundled language |
| `ulm remove <language-id>` | Remove an installed language |
| `ulm list` | List installed languages |
| `ulm info <language-id>` | Show language details |
| `ulm update [language-id]` | Reinstall from bundled assets |
| `ulm enable <language-id>` | Enable its Xed file type |
| `ulm disable <language-id>` | Disable its Xed file type |
| `ulm status` | Show manager status |
| `ulm test-online` | Download and validate the online catalog and Astro grammar |
| `ulm version` | Show ULM version |

Example:

```sh
ulm search astro
ulm install astro
ulm list
ulm info astro
ulm test-online
```

## Runtime design

- A small `ulm` shell launcher sends requests through a private filesystem bridge to the loaded extension.
- `language-packs/languages.json` is the online source of language metadata; bundled metadata is the fallback.
- Remote files are fetched from GitHub Raw over HTTPS with connection/read timeouts and a 20 MB per-asset limit.
- Grammar/configuration assets are copied to `local/home/.local/share/ulm/grammars/<id>/` when installed.
- A generated runtime registry is written to the ULM private data directory and loaded through Sora TextMate's `GrammarRegistry`.
- `FileType.kt` registers/unregisters the matching Xed file type from the same metadata.
- Installed languages persist across extension reloads.

## Scope and limitations

- ULM manages syntax highlighting and language file-type registration only. It does not install language servers, compilers, runtimes, debuggers, or formatters.
- The online catalog currently includes Astro only; more languages can be added under `language-packs/<id>/` with a matching registry entry.
- TextMate's global registry has no public unload API. Restart Xed after removing a grammar to fully clear already-loaded grammar data.
- Actual editor behavior must be tested in Xed-Editor.

## Build

```sh
./compileDebug
./compileRelease
```

The extension package is written to `output/io.github.orange.universal.xed`.

## License

The extension is MIT-licensed. The Astro grammar is sourced from the Astro language-tools project and is distributed under its upstream MIT license.
