/**
 * Translation, done by a model big enough to sound human.
 *
 * This exists because the key cannot live in the app. An APK is a zip; anyone can pull the
 * strings out of it, and a Groq key found that way spends the owner's money until it is noticed.
 * So the key stays here, in a Vercel environment variable, and the app talks to this instead.
 *
 * The app still ships ML Kit and still falls back to it. That matters: ML Kit works with the
 * network off and costs nothing, it just writes textbook Hindi. This endpoint is the upgrade,
 * not the requirement — if it is down, or the key is unset, translation keeps working.
 */

const GROQ_URL = 'https://api.groq.com/openai/v1/chat/completions';
const MODEL = 'llama-3.3-70b-versatile';

/** Long enough for a caption or a paragraph, short enough that nobody translates a book on your bill. */
const MAX_CHARS = 4000;

/**
 * The whole point of using a real model instead of ML Kit is register, so the prompt is almost
 * entirely about register. "Natural" on its own gets you formal Hindi back, because that is what
 * most Hindi on the internet is.
 */
const SYSTEM = `You translate short pieces of text for a phone app.

Rules:
- Reply with the translation and nothing else. No preface, no quotes, no explanation, no notes.
- Into Hindi: write the Hindi people actually speak. Everyday spoken register, Devanagari script.
  Use बहुत not अत्यंत, लेकिन not परन्तु, और not एवं, ज़रूरी not आवश्यक, इस्तेमाल not प्रयोग.
  Use गर्मी not ग्रीष्म, सुबह not प्रातः, कीमत not मूल्य.
  If a word has no everyday Hindi equivalent, keep the English word in Devanagari rather than
  inventing one: गिवअवे, ऑफर, डिस्काउंट, अकाउंट, अपडेट. Hindi speakers say these. A translated
  substitute like देन for "giveaway" is worse than the English word, because nobody says it.
  Do not reach for Sanskrit.
- Into English: plain, ordinary English. Same meaning, no embellishment.
- Keep the original's line breaks, names, @handles, URLs and prices exactly.
- Digits stay as Western digits: 28, 1,000,000. Never Devanagari numerals — nobody reads a price
  as १,०००,००० and the model volunteers it if you do not say so.
- Prefer the verb people say: पाएं or मिलेंगे over प्राप्त करें, देखें over अवलोकन करें.
- If it is already in the target language, return it unchanged.`;

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const key = process.env.GROQ_API_KEY;
  // A missing key is not an error worth shouting about — the app simply uses ML Kit. Saying so
  // in the response is what lets the app tell "not configured" apart from "broken".
  if (!key) return res.status(503).json({ error: 'unconfigured' });

  const { text, to } = req.body || {};
  if (typeof text !== 'string' || !text.trim()) {
    return res.status(400).json({ error: 'text required' });
  }
  if (text.length > MAX_CHARS) {
    return res.status(413).json({ error: 'too long' });
  }
  const target = to === 'en' ? 'English' : 'Hindi';

  try {
    const upstream = await fetch(GROQ_URL, {
      method: 'POST',
      headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: MODEL,
        temperature: 0.2,
        max_tokens: 1200,
        messages: [
          { role: 'system', content: SYSTEM },
          { role: 'user', content: `Translate into ${target}:\n\n${text}` },
        ],
      }),
      signal: AbortSignal.timeout(20_000),
    });

    if (!upstream.ok) {
      const detail = await upstream.text();
      console.error('groq', upstream.status, detail.slice(0, 300));
      return res.status(502).json({ error: 'upstream' });
    }

    const data = await upstream.json();
    const out = data?.choices?.[0]?.message?.content?.trim();
    if (!out) return res.status(502).json({ error: 'empty' });

    // Models like to wrap a translation in quotes even when told not to.
    const cleaned = out.replace(/^["'“”]+|["'“”]+$/g, '').trim();
    return res.status(200).json({ text: cleaned });
  } catch (e) {
    console.error('translate', e?.message);
    return res.status(504).json({ error: 'timeout' });
  }
}
