// Deletes the calling user's account and all their data.
// JWT verification stays ON (the default): only a signed-in user can delete themselves.
import { createClient } from "npm:@supabase/supabase-js@2";

const URL = Deno.env.get("SUPABASE_URL")!;

Deno.serve(async (req) => {
  // Who is asking? (from their own login token, never from the request body)
  const asUser = createClient(URL, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } },
  });
  const { data: { user } } = await asUser.auth.getUser();
  if (!user) return new Response("Unauthorized", { status: 401 });

  const admin = createClient(URL, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  // 1. Keep a fingerprint of the number if there are open reports against them
  const { error: holdError } = await admin.rpc("prepare_account_deletion", { p_user: user.id });
  if (holdError) {
    console.error("prepare_account_deletion failed", holdError);
    return new Response("Couldn't prepare deletion", { status: 500 });
  }

  // 2. Photo files (database rows don't remove the actual files)
  const { data: files } = await admin.storage.from("photos").list(user.id, { limit: 100 });
  if (files?.length) {
    await admin.storage.from("photos").remove(files.map((f) => `${user.id}/${f.name}`));
  }

  // 3. The account itself; everything else is removed by the database's cascade rules
  const { error } = await admin.auth.admin.deleteUser(user.id);
  if (error) {
    console.error("deleteUser failed", error);
    return new Response("Couldn't delete account", { status: 500 });
  }

  return Response.json({ deleted: true });
});