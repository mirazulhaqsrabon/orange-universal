# Universal Language Manager

A Xed-Editor extension for installing and managing TextMate syntax-highlighting grammars at runtime.

## Current language registry

ULM currently ships one self-hosted language pack:

- **Astro** — `.astro` files, frontmatter, Astro components, and Astro code fences in MDX.

Language packs are hosted in this repository under `ulm/`. ULM does not query or download packages from Open VSX.

## Install the extension

1. Download the latest `.xed` package from the successful GitHub Actions workflow artifact.
2. In Xed-Editor, open **Settings → Store → Install from storage**.
3. Select the downloaded `.xed` file.
4. Open the Xed Ubuntu terminal and run:

```sh
ulm help
```

The extension installs the `ulm` launcher into Xed's shared terminal bin directory when it loads. It does not install a command into unrelated Android or Ubuntu installations.

## Commands

| Command | Description |
| --- | --- |
| `ulm help` | Show command help |
| `ulm search <query>` | Search the self-hosted ULM registry |
| `ulm install astro` | Download and register the Astro grammar |
| `ulm remove astro` | Remove the Astro grammar |
| `ulm list` | List installed languages |
| `ulm info astro` | Show grammar details |
| `ulm update [astro]` | Update one or all installed packs |
| `ulm enable astro` | Enable the Astro file type |
| `ulm disable astro` | Disable the Astro file type |
| `ulm status` | Show manager status |
| `ulm version` | Show version |

Example:

```sh
ulm search astro
ulm install astro
ulm list
ulm info astro
```

## Registry layout

```text
ulm/
├── registry.json
└── languages/
    └── astro/
        ├── metadata.json
        ├── LICENSE
        ├── language-configuration.json
        └── grammars/
            ├── astro.tmLanguage.json
            └── mdx.astro.tmLanguage.json
```

The registry metadata records language IDs, extensions, TextMate scopes, grammar paths, versions, and optional injection grammars. The Astro grammar is sourced from the Astro language-tools project and retains its MIT license and attribution.

## How it works

- The extension registers a small `ulm` shell launcher in Xed's own Ubuntu environment.
- The launcher sends requests through a private shared-filesystem bridge to the loaded extension.
- The extension reads the registry and language files from this repository over HTTPS.
- Grammar files are exposed to Sora TextMate through a runtime `FileResolver`.
- Installed language metadata and grammar files are stored under Xed's private shared home directory, so they persist across extension reloads.

## Scope and limitations

- ULM manages TextMate syntax highlighting only. It does not install language servers, compilers, runtimes, debuggers, or formatters.
- Astro embedded-language highlighting depends on the corresponding TextMate grammars being available in Xed.
- TextMate's global registry has no public unload API. After removing or updating a grammar that was already loaded, restart Xed-Editor to fully clear the old in-memory grammar.
- Device installation and editor-runtime behavior must be tested in Xed-Editor.

## Development

Build the Xed package:

```sh
./compileDebug
./compileRelease
```

The package is written to `output/io.github.orange.universal.xed`.

## License

The extension is MIT-licensed. The Astro grammar files include their upstream MIT license in `ulm/languages/astro/LICENSE`.
