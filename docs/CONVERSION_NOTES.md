# Hugo to MkDocs Conversion Notes

## Overview

The Viaduct public website has been successfully converted from Hugo/Docsy to MkDocs with Material for MkDocs theme.

## What Was Done

### 1. Created MkDocs Configuration
- Created `mkdocs.yml` with Material for MkDocs theme
- Configured full navigation structure matching the original Hugo site
- Set up support for Kotlin, Java, GraphQL, YAML, and JSON syntax highlighting
- Configured markdown extensions for admonitions, code highlighting, and tables
- Enabled Material theme features: navigation tabs, instant loading, search suggestions, code copy buttons
- Configured purple color scheme to match Viaduct branding
- Enabled light/dark mode toggle

### 2. Converted Content
- All markdown files from `content/` have been converted to `docs/`
- Hugo shortcodes have been converted to MkDocs-compatible markdown:
  - `{{% blocks/cover %}}` → removed (cover blocks)
  - `{{% blocks/lead %}}` → blockquotes
  - `{{% pageinfo %}}` → `!!! info` or `!!! note` admonitions
  - `{{< kdoc ... >}}` → inline code
  - `{{< prevnext >}}` → removed
  - `{{< codetag >}}`, `{{< codefile >}}`, `{{< github >}}` → GitHub links with placeholders
  - `{{% imgproc %}}` → removed (image processing blocks)

### 3. Directory Structure
```
docs/
├── mkdocs.yml          # MkDocs configuration
├── docs/               # Converted documentation content
│   ├── index.md        # Homepage
│   ├── about/
│   ├── getting_started/
│   ├── developers/
│   ├── service_engineers/
│   ├── contributors/
│   ├── tutorials/
│   ├── blog/
│   ├── roadmap/
│   └── community/
├── content/            # Original Hugo content (can be removed)
├── layouts/            # Original Hugo layouts (can be removed)
├── static/             # Original Hugo static files (can be removed after copying APIs)
└── convert_hugo_to_mkdocs.py  # Conversion script
```

### 4. Updated Documentation
- README.md updated with MkDocs instructions
- Documented how to run locally with `mkdocs serve`
- Documented deployment process

## Known Limitations & Notes

### Custom Shortcodes

✅ **Custom macros have been implemented** for code embedding functionality:

1. **`codetag`**: ✅ Implemented as MkDocs macro
   - Extracts code snippets marked with tags in source files
   - Usage: `{{ codetag("path/to/file.kt", "TAG_NAME", lang="kotlin") }}`
   - Reads from the Viaduct repository and extracts tagged sections

2. **`codefile`**: ✅ Implemented as MkDocs macro
   - Embeds entire files or specific line ranges
   - Usage: `{{ codefile("path/to/file.kt", start=10, end=20, lang="kotlin") }}`
   - Supports optional start/end line parameters

3. **`github`**: ✅ Implemented as MkDocs macro
   - Embeds GitHub files with links
   - Usage: `{{ github("path/to/file.kt#L10-L20") }}`
   - Supports line range notation

4. **`kdoc`**: ✅ Implemented as MkDocs macro
   - Generates links to Dokka API documentation
   - Usage: `{{ kdoc("viaduct.api.Resolver", "@Resolver") }}`
   - Automatically determines module (tenant-api or service) from package prefix
   - Converts class names to URL slugs (CamelCase → kebab-case)

**Implementation Details:**
- Custom macros are defined in `plugins/code_snippets.py`
- Uses `mkdocs-macros-plugin` for macro processing
- All 18 files using these macros have been updated to use the new syntax

### Static Assets
- CSS files (`kdoc-*.css`) have been copied to `docs/`
- Images have been copied to `docs/img/`
- API documentation (Dokka) is generated to `docs/apis/` and is automatically included in the MkDocs build

### Compatibility with Internal Techdocs
The converted markdown files are now standard markdown compatible with:
- MkDocs (for viaduct.airbnb.tech public website)
- Airbnb internal techdocs system

This enables publishing the same content to both destinations without duplication.

## Next Steps

### Optional Enhancements
1. **Customize Material Theme**: Consider customizing the Material theme further:
   - Add custom color schemes
   - Configure additional features from [Material for MkDocs documentation](https://squidfunk.github.io/mkdocs-material/)
   - Add social cards for better sharing

2. **Enhance Search**: Material theme includes powerful search, but you can:
   - Enable search highlighting
   - Configure search boosting for important pages

### Cleanup (After Verification)
Once you've verified the MkDocs site works correctly, you can remove:
- `content/` directory (original Hugo content)
- `layouts/` directory (Hugo layouts)
- `hugo.yaml` (Hugo configuration)
- `go.mod` and `go.sum` (Hugo module files)
- `node_modules/`, `package.json`, `package-lock.json` (if not needed for other purposes)
- `.hugo_build.lock`

## Testing
To test the converted site locally:
```bash
pip install mkdocs
cd /path/to/docs
mkdocs serve
```

Then visit `http://localhost:8000`

## Deployment
Update your CI/CD pipeline to:
1. Run `mkdocs build` instead of `hugo build`
2. Deploy from `site/` directory instead of `public/`
3. Continue to generate Dokka documentation as before
