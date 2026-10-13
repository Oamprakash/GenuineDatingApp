// The vendor calls this when liveness + ID + face match finishes.
// Only this function (service role) can set verification = 'verified'.
// Deploy with: supabase functions deploy verification-webhook --no-verify-jwt
import { createClient } from "npm:@supabase/supabase-js@2";

Deno.serve(async (req) => {
  // TODO: verify the vendor's signature header instead of a shared secret.
  if (req.headers.get("x-webhook-secret") !== Deno.env.get("VERIFY_WEBHOOK_SECRET")) {
    return new Response("Forbidden", { status: 403 });
  }
  const body = await req.json(); // field names depend on your vendor
  const userId: string = body.reference_id;
  const passed: boolean = body.liveness_passed && body.id_valid && body.face_match;

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  await admin.from("profiles")
    .update({ verification: passed ? "verified" : "rejected" })
    .eq("id", userId);

  // Store only the outcome. Never keep ID images or numbers (DPDP Act).
  return new Response("ok");
});
