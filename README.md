# Universal Language Manager

A Xed-Editor extension that installs and manages TextMate language grammars at runtime.

## Requirements

- Xed-Editor with extension support (minimum app version code: 95)
- Xed Ubuntu terminal enabled
- Internet access for searching and downloading grammar packs

## Install

1. Download the latest `.xed` package from the GitHub Actions artifact.
2. In Xed-Editor, open **Settings → Store → Install from storage**.
3. Select the downloaded `.xed` file.
4. Open or restart the Xed Ubuntu terminal, then run:

```sh
ulm help
```

The extension installs the `ulm` launcher into Xed's shared terminal bin directory when it loads. It does not install a command into unrelated Android or Ubuntu installations.

## Commands

| Command | Description |
| --- | --- |
| `ulm help` | Show command help |
| `ulm search <query>` | Search Open VSX |
| `ulm install <id or language>` | Download a VSIX and register its TextMate grammars |
| `ulm remove <language-id>` | Remove an installed language |
| `ulm list` | List installed languages |
| `ulm info <language-id>` | Show grammar and package details |
| `ulm update [language-id]` | Update an installed package |
| `ulm enable <language-id>` | Enable its file type |
| `ulm disable <language-id>` | Disable its file type |
| `ulm status` | Show manager status |
| `ulm version` | Show version |

Example:

```sh
ulm search python
ulm install ms-python.python
ulm list
ulm info python
```

Package IDs are provided by Open VSX. Search first if you are unsure of the exact ID.

## How it works

- The extension registers a small `ulm` shell launcher in Xed's own Ubuntu environment.
- The launcher sends requests through a private shared-filesystem bridge to the loaded extension.
- The extension queries Open VSX, downloads VSIX packages, validates package paths and extracts TextMate grammar/configuration files.
- Grammar files are exposed to Sora TextMate through a runtime `FileResolver`.
- Installed language metadata and grammar files are stored under Xed's private shared home directory, so they persist across extension reloads.

## Notes and limitations

- This manages TextMate syntax grammars; it does not install language servers, compilers, runtimes, debuggers, or formatters.
- A package may contain grammars that depend on other grammars or files. Packs with unusual external dependencies may not work fully.
- TextMate's global registry has no public unload API. After removing or updating a grammar that was already loaded, restart Xed-Editor to fully clear the old in-memory grammar.
- A language pack may define multiple languages. Use the language IDs shown by `ulm list` and `ulm info`.
- Only use grammar packs from publishers you trust.

## Development

Build the Xed package:

```sh
./compileDebug
./compileRelease
```

The package is written to `output/io.github.orange.universal.xed`.

## License

MIT
