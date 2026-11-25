# Orange

Orange is a Xed-Editor extension that detects the active file type and downloads the matching TextMate syntax pack.

## Features

- One **Orange** action in the editor menu and toolbar
- Detects the active file extension automatically
- Downloads and validates matching grammar and language configuration files
- Registers the language with Xed-Editor
- Restores installed language packs after the extension reloads
- Displays clear download results with the project owner attribution

## Use

1. Open a source file in Xed-Editor.
2. Select **Orange** from the editor menu or toolbar.
3. Orange detects the file extension and checks the online catalog.
4. If a matching pack is available, Orange downloads, validates, and installs it.

## Online language catalog

The catalog is stored at `language-packs/languages.json`. Each language pack lives in `language-packs/<id>/`.

Currently available:

- **Astro** — `.astro` files

## Add a language pack

1. Create `language-packs/<id>/`.
2. Add the TextMate grammar and optional language configuration.
3. Add the pack metadata to `language-packs/languages.json`.
4. Ensure the catalog `scopeName` matches the grammar's `scopeName`.
5. Verify the raw GitHub asset URLs and JSON files.

## Build

Requirements: JDK 21 and Android SDK/build tools compatible with the Xed-Editor extension SDK.

```sh
./compileDebug
./compileRelease
```

The packaged extension is written to `output/io.github.orange.universal.xed`.

## Data and compatibility

Orange stores its data under `~/.local/share/orange`. On first launch after upgrading from an earlier build, it attempts a one-time migration of the previous language-pack data so installed packs remain available.

## Project

- **Owner:** Miraz
- **Repository:** https://github.com/mirazulhaqsrabon/orange-universal
- **License:** MIT

The Astro grammar retains its upstream license notice in `language-packs/astro/ASTRO-LICENSE.txt`.
