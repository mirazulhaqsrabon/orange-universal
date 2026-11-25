# Orange

Orange is a Xed-Editor extension with one simple editor-menu action. Tap **Orange** while a file is open and it detects that file's extension, downloads the matching TextMate grammar from the GitHub language catalog, and installs it into Xed.

## How it works

1. Open a file in Xed.
2. Open the editor menu and tap **Orange**.
3. Orange detects the active file extension (for example, `.astro`).
4. It downloads and validates the matching grammar/configuration from this repository's online language pack registry, then installs it.

There is no separate ULM command, command palette, or keyboard shortcut. Orange is the only extension menu action.

## Online language catalog

Orange reads `language-packs/languages.json` from this branch. Grammar and configuration files are downloaded from `language-packs/<id>/`.

Current online language pack:

- **Astro** — `.astro` files, using the Astro TextMate grammar.

## Add another language

1. Create `language-packs/<id>/` on the `feature/orange-menu-download` branch.
2. Add its TextMate grammar and optional language configuration to that folder.
3. Add an entry to `language-packs/languages.json` with matching file names, extensions and TextMate scope.
4. Ensure the registry `scopeName` exactly matches the grammar file's `scopeName`.
5. Verify the GitHub Raw URLs and JSON files.

## Install the extension

1. Download the `.xed` package from a successful GitHub Actions build artifact.
2. In Xed-Editor, open **Settings → Store → Install from storage**.
3. Select the downloaded `.xed` file.

## Runtime design

- The **Orange** menu command works on the currently active editor file.
- The extension reads the online language catalog and matches the file extension.
- Grammar/configuration assets are fetched from GitHub Raw over HTTPS, validated, and installed into Xed's private data directory.
- Installed languages persist across extension reloads.
- If a matching pack is already installed, tapping Orange downloads and installs it again so the local copy is refreshed.
- If no matching language pack exists, Orange displays a clear message.

## Scope and limitations

- Orange manages TextMate syntax highlighting and Xed file-type registration only. It does not install language servers, compilers, runtimes, debuggers, or formatters.
- The online catalog currently includes Astro only; more packs can be added under `language-packs/<id>/` with a matching registry entry.
- Actual editor behavior must be tested in Xed-Editor.

## Build

```sh
./compileDebug
./compileRelease
```

The extension package is written to `output/io.github.orange.universal.xed`.

## License

The extension is MIT-licensed. The Astro grammar is sourced from the Astro language-tools project and is distributed under its upstream MIT license.
