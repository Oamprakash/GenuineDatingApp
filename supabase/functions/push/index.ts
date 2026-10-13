// Sends a notification to every phone registered for a user, via FCM HTTP v1.
// Called only by the database (notify_user), authenticated with a shared secret.
import { createClient } from "npm:@supabase/supabase-js@2";
import { importPKCS8, SignJWT } from "npm:jose@5";

const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT")!);
const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

let cached: { token: string; exp: number } | null = null;

/** Google OAuth token for FCM, cached for ~1 hour. */
async function accessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;

  const key = await importPKCS8(sa.private_key, "RS256");
  const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(key);

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`OAuth failed: ${JSON.stringify(json)}`);
  cached = { token: json.access_token, exp: now + json.expires_in };
  return cached.token;
}

Deno.serve(async (req) => {
  if (req.headers.get("x-push-secret") !== Deno.env.get("PUSH_SECRET")) {
    return new Response("Forbidden", { status: 403 });
  }
  const { user_id, title, body, data } = await req.json();

  const { data: rows } = await admin.from("device_tokens").select("token").eq("user_id", user_id);
  if (!rows?.length) return new Response("no devices");

  const at = await accessToken();
  // FCM data values must be strings; title/body are included so the app can build it in the foreground
  const strData: Record<string, string> = Object.fromEntries(
    Object.entries({ ...(data ?? {}), title, body }).map(([k, v]) => [k, String(v)]),
  );
  const channel =
    ({ message: "messages", match: "matches", reminder: "reminders", checkin: "reminders" } as Record<string, string>)[strData.type]
    ?? "meetups";

  await Promise.all(rows.map(async ({ token }) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${at}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: { title, body },
          data: strData,
          android: {
            priority: "high",
            // Same tag = replaces the previous notification for this chat instead of stacking
            notification: {
              channel_id: channel,
              tag: channel === "reminders" ? `${strData.type}:${strData.match_id}` : (strData.match_id ?? channel),
            },
          },
        },
      }),
    });
    if (!res.ok) console.error("FCM error", res.status, await res.text());
    // Phone uninstalled the app or the registration expired: forget it
    if (res.status === 404) await admin.from("device_tokens").delete().eq("token", token);
  }));

  return new Response("ok");
});