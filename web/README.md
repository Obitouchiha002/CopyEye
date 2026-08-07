# CopyEye — website

Two self-contained pages. No build step, no dependencies, no external requests: every style,
script and graphic is inline, which is also why they score well on Core Web Vitals.

| File | What it is |
|---|---|
| `index.html` | The landing page. Hero is a WebGL eye written by hand — no library, because the whole pitch is that this app never calls out. On a phone the page becomes five swipeable screens with a bottom tab bar. |
| `developer.html` | Vansh Kashyap's profile: apps, client work, stack, background, contact. |

## Deploy

Any static host works. For Vercel:

```bash
cd web
npx vercel --prod
```

Then point a domain at it and **update these three places to match**:

| Where | What |
|---|---|
| `index.html` and `developer.html` → `<link rel="canonical">` | the real URL |
| both files → `og:url` and every JSON-LD `@id` | the same URL |
| `robots.txt` and `sitemap.xml` | the same URL |

They currently all say `https://copyeye.vercel.app/`.

## The APK

The download button points at `./CopyEye-arm64.apk`. Drop the signed APK next to
`index.html` before deploying, or change the link to a GitHub release URL.

APKs are gitignored, so the file is not in this repository.

## After it is live

On-page SEO is done — titles, descriptions, Open Graph, and JSON-LD for the app, the
author and the FAQ. What a page cannot do for itself is earn authority, so:

1. Submit the sitemap in Google Search Console.
2. **Link back from the portfolios.** `developer.html` already points at
   `vanshkashyap.lzworth.in` and `techbyvansh.lzworth.in` with `rel="me"`, and declares a
   `Person` with `sameAs` covering both. That half only counts once the other half exists:
   add a link to `copyeye.vercel.app` on each portfolio. Mutual links are what let a search
   engine merge the three into one identity, which is what makes a search for the name and a
   search for the app surface each other.
3. Add an `og-image.png` (1200×630) and reference it from `og:image`; social previews
   without one get far fewer clicks.
