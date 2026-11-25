# Grammar Registry

This directory contains the remote language registry used by Orange Universal.

Each entry in `index.json` defines a unique id, display title, file extensions,
TextMate scope name, grammar URL, and an optional Xed language-configuration URL.

Grammar sources must be JSON TextMate grammars whose top-level `scopeName`
matches the registry entry. The installer validates the scope before loading
the grammar and stores downloaded files in the extension's private storage.

Only the trusted `raw.githubusercontent.com` host is accepted by the current
installer. Add languages by updating the registry with verified grammar
sources and their applicable license/attribution details.
