# Release notes

`generate_release_notes` (see `.github/workflows/release.yml`) writes the usual
"What's Changed" list from the commits. That list cannot explain *why* a change
matters or what a fresh install gets, so a release can carry its own summary:

- Add `<tag>.md` here (for example `v1.1.30.md`) and it is prepended to the
  generated notes when that tag is released. Keep it in the repository even
  after the release so the text stays reviewable.
- Nothing here for a tag means the release is just the generated list.
- The file is copied verbatim, so write it the way it should read on GitHub
  (Markdown, `##` headings, no front matter).
