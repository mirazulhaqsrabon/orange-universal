# Universal Language Manager

A Xed-Editor extension that installs and manages TextMate syntax highlighting from language assets bundled inside the extension.

## Language catalog

ULM uses one metadata file: `app/src/main/assets/languages.json`. Grammar and language-configuration files live directly beside it in the same flat assets directory.

Current bundled language:

- **Astro** — `.astro` files, using the Astro TextMate grammar.

## Asset layout

```text
app/src/main/assets/
├── languages.json
├── astro.tmLanguage.json
└── Astro.config.json
```

There are no per-language asset folders and no remote language registry. The runtime reads the catalog, copies the selected grammar/configuration into Xed's private ULM data directory, creates the active TextMate registry, and registers the matching Xed `FileType`.

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
| `ulm version` | Show ULM version |

Example:

```sh
ulm search astro
ulm install astro
ulm list
ulm info astro
```

## Runtime design

- A small `ulm` shell launcher sends requests through a private filesystem bridge to the loaded extension.
- `languages.json` is the single source of language metadata.
- Grammar/configuration assets are copied to `local/home/.local/share/ulm/grammars/<id>/` when installed.
- A generated runtime registry is written to the ULM private data directory and loaded through Sora TextMate's `GrammarRegistry`.
- `FileType.kt` registers/unregisters the matching Xed file type from the same metadata.
- Installed languages persist across extension reloads.

## Scope and limitations

- ULM manages syntax highlighting and language file-type registration only. It does not install language servers, compilers, runtimes, debuggers, or formatters.
- The current catalog includes Astro only; more languages can be added by placing their files in the flat assets directory and adding metadata.
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
