// Vercel serverless function: the "brain" of Hello.
// The Android app sends {goal, history, screen, step}; Gemini decides the next action(s).
// Env vars (Vercel -> Settings -> Environment Variables):
//   GEMINI_API_KEY  your Gemini key (never ships in the APK)
//   AGENT_TOKEN     long random string; the app sends it so strangers can't burn your quota
//   GEMINI_MODEL    optional, default gemini-2.5-flash

const MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";
const MAX_STEPS = 25;

const SYSTEM = `You are "Hello", an agent that operates an Android tablet (Samsung, One UI) for its owner Alok.
You are shown the CURRENT screen as a numbered list of UI elements and you reply with the next action(s) as JSON.

Screen line format:  [id] ClassName "text" (desc: "content description") flags
flags: click = tappable, edit = text box, scroll = scrollable, checked = ticked.

Reply with ONLY this JSON, no markdown:
{"thought": "<one short Hinglish line: what you see and why this action>",
 "actions": [ {"type": "..."} ]}

Action types:
- {"type":"click","id":N}            tap element N
- {"type":"type","id":N,"text":"..."} put text in edit box N (replaces content)
- {"type":"scroll","dir":"down"|"up"} scroll the main list
- {"type":"back"}   {"type":"home"}
- {"type":"open_app","name":"claude"} open an installed app by its launcher name
- {"type":"wait"}                     screen is loading; look again shortly
- {"type":"ask_user","question":"..."} you need the human (see rules)
- {"type":"done","summary":"..."}     the goal is achieved (or impossible: say why)

Rules:
1. Use element ids from the CURRENT screen only. Ids change every step.
2. Normally return ONE action. Return several only when the screen cannot change in between
   (example: type into a box, then click its Send button on the same screen). Never chain clicks that navigate.
3. Never type or guess passwords, OTP/verification codes, card numbers, UPI PINs, or solve captchas.
   Use ask_user (e.g. "Password khud daalo, phir 'ho gaya' likho").
4. Before anything irreversible or involving money (pay, buy, delete account, send money), use ask_user to confirm.
5. Logging in with Google: pick the account the user named. If an account chooser shows the right email, tap it.
6. If an action did not change the screen, do NOT repeat it more than twice. Try another route: scroll, back, a different element.
7. If the app is not the one you need, use open_app. If the goal is already satisfied on screen, return done.
8. Be efficient: the owner has a small daily request quota. Do not waste steps.
9. Write thought, question and summary in short Hinglish.`;

function parseModelJson(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    const m = text.match(/\{[\s\S]*\}/);
    if (!m) return null;
    try {
      return JSON.parse(m[0]);
    } catch (e2) {
      return null;
    }
  }
}

module.exports = async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });

  const token = process.env.AGENT_TOKEN;
  if (!token || req.headers["x-agent-token"] !== token) {
    return res.status(401).json({ error: "bad token" });
  }
  if (!process.env.GEMINI_API_KEY) {
    return res.status(500).json({ error: "GEMINI_API_KEY missing on server" });
  }

  const body = req.body || {};
  const goal = String(body.goal || "").slice(0, 2000);
  const history = Array.isArray(body.history) ? body.history.slice(-14).map(String) : [];
  const screen = String(body.screen || "").slice(0, 14000);
  const step = Number(body.step || 1);
  if (!goal) return res.status(400).json({ error: "goal missing" });

  const userText =
    `GOAL: ${goal}\n` +
    `STEP: ${step} of ${MAX_STEPS}\n\n` +
    `PREVIOUS ACTIONS (oldest first):\n${history.length ? history.join("\n") : "(none yet)"}\n\n` +
    `CURRENT SCREEN:\n${screen}`;

  let r;
  try {
    r = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": process.env.GEMINI_API_KEY,
        },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: SYSTEM }] },
          contents: [{ role: "user", parts: [{ text: userText }] }],
          generationConfig: { temperature: 0.2, responseMimeType: "application/json" },
        }),
      }
    );
  } catch (e) {
    return res.status(502).json({ error: "gemini unreachable: " + e.message });
  }

  if (!r.ok) {
    const t = (await r.text()).slice(0, 300);
    return res.status(r.status === 429 ? 429 : 502).json({ error: "gemini " + r.status + ": " + t });
  }

  const j = await r.json();
  const parts = (j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [];
  const text = parts.map((p) => p.text || "").join("");
  const out = parseModelJson(text);
  if (!out || !Array.isArray(out.actions)) {
    return res.status(502).json({ error: "bad model output", raw: text.slice(0, 300) });
  }
  return res.status(200).json(out);
};
