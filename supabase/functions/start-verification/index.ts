// Creates a liveness + ID verification session with your vendor
// (HyperVerge / IDfy / Signzy) and returns the URL the app opens.
import { createClient } from "npm:@supabase/supabase-js@2";

Deno.serve(async (req) => {
  const auth = req.headers.get("Authorization") ?? "";
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: auth } } },
  );
  const { data: { user } } = await supabase.auth.getUser();
  if (!user) return new Response("Unauthorized", { status: 401 });

  // TODO: replace with the vendor's "create session" API call.
  // Pass user.id as the reference ID so the webhook can map the result back.
  const session = { url: `https://verify.example.com/start?ref=${user.id}` };

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  await admin.from("profiles").update({ verification: "in_review" }).eq("id", user.id);

  return Response.json(session);
});
