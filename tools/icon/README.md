# App icon

`render.js` draws the icon as vectors and renders every size the apps
use (iOS 1024 px light / dark / tinted; Android adaptive background,
foreground and monochrome layers for each density):

    node tools/icon/render.js classic --apply

Variants: `classic` (the pages and the name, in use), `pages` (the pages
alone, larger), `violet` (white pages on violet). `svg/` holds each
variant's layers. Needs Playwright with Chromium, and the Inter font.
