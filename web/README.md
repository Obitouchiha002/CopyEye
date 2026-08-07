# CopyEye — landing page

One self-contained `index.html`. No build step, no dependencies, no external requests:
every style, script and graphic is inline, which is also why it scores well on Core Web Vitals.

## Deploy

Any static host works. For Vercel:

```bash
cd web
npx vercel --prod
```

Then point a domain at it and **update these three places to match**:

| Where | What |
|---|---|
| `index.html` → `<link rel="canonical">` | the real URL |
| `index.html` → `og:url` and the JSON-LD `@id` values | the same URL |
| `robots.txt` and `sitemap.xml` | the same URL |

They currently all say `https://copyeye.lzworth.in/`.

## The APK

The download button points at `./CopyEye-arm64.apk`. Drop the signed APK next to
`index.html` before deploying, or change the link to a GitHub release URL.

APKs are gitignored, so the file is not in this repository.

## After it is live

On-page SEO is done — titles, descriptions, Open Graph, and JSON-LD for the app, the
author and the FAQ. What a page cannot do for itself is earn authority, so:

1. Submit the sitemap in Google Search Console.
2. Link to this page from `vanshkashyap.lzworth.in` and `techbyvansh.lzworth.in`, and link
   back — mutual links between the three sites are what tie them into one identity that
   search engines can recognise.
3. Add an `og-image.png` (1200×630) and reference it from `og:image`; social previews
   without one get far fewer clicks.
