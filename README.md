# Orange

A Xed-Editor extension that detects the active file type and downloads TextMate syntax packs directly from a GitHub-hosted registry. Language grammar files are not bundled in the extension APK.

## Online language catalog

ULM reads `language-packs/languages.json` from this repository's `feature/detect-install-syntax` branch. Grammar and configuration files are downloaded from `language-packs/<id>/`.

Current online language pack:

- **Astro** — `.astro` files, using the Astro TextMate grammar.

## Remote asset layout

```text
language-packs/
├── languages.json
└── astro/
    ├── astro.tmLanguage.json
    ├── Astro.config.json
    └── ASTRO-LICENSE.txt
```

There are no bundled language assets under `app/src/main/assets/`. The runtime fetches the catalog and the selected language files from GitHub Raw over HTTPS, validates the downloaded grammar scope and JSON configuration, then installs them into Xed's private ULM data directory. If GitHub cannot be reached, installation fails with an error instead of silently using a bundled copy.

## Add another language

1. Create `language-packs/<id>/` on the feature branch.
2. Add its TextMate grammar and optional language configuration to that folder.
3. Add an entry to `language-packs/languages.json` with the matching file names, extensions and TextMate scope.
4. Ensure the registry `scopeName` exactly matches the grammar file's `scopeName`.
5. Run `ulm test-online` and then `ulm install <id>` in Xed.

The `id` must be unique. Grammar and configuration filenames must be root-level files inside that language's folder. Each language can list one or more file extensions.

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

The extension registers **Orange** as an editor toolbar action. Xed may place overflow toolbar actions inside the three-dot menu depending on its layout. Selecting Orange opens the extension's command group with **Universal Language Manager** and **Detect & Download Syntax** actions. Actual placement depends on the Xed version and toolbar layout.

## Active-file syntax detection

The **Detect & Download Syntax** action reads the active file's extension and installs or enables the matching language pack from the online catalog. The default shortcut is **Ctrl+Shift+L**.

The online catalog currently contains Astro only, so `.astro` is the only supported remote pack. Add more packs under `language-packs/<id>/` and register them in `language-packs/languages.json`.

## Commands

| Command | Description |
| --- | --- |
| `ulm help` | Show command help |
| `ulm search <query>` | Search the online language catalog |
| `ulm install <language-id>` | Download and install a language from GitHub |
| `ulm remove <language-id>` | Remove an installed language |
| `ulm list` | List installed languages |
| `ulm info <language-id>` | Show language details |
| `ulm update [language-id]` | Re-download and reinstall language assets |
| `ulm enable <language-id>` | Enable its Xed file type |
| `ulm disable <language-id>` | Disable its Xed file type |
| `ulm status` | Show manager status |
| `ulm test-online` | Download and validate the GitHub catalog, Astro grammar and configuration |
| `ulm version` | Show ULM version |

Example:

```sh
ulm search astro
ulm test-online
ulm install astro
ulm list
ulm info astro
```

## Runtime design

- A small `ulm` shell launcher sends requests through a private filesystem bridge to the loaded extension.
- `language-packs/languages.json` is the only language metadata source; there is no bundled catalog fallback.
- Remote files are fetched from GitHub Raw over HTTPS with connection/read timeouts and a 20 MB per-asset limit.
- Grammar/configuration assets are downloaded into a staging directory, validated, then installed to `local/home/.local/share/ulm/grammars/<id>/`.
- A generated runtime registry is written to the ULM private data directory and loaded through Sora TextMate's `GrammarRegistry`.
- `FileType.kt` registers/unregisters the matching Xed file type from the downloaded metadata.
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
