# Genuine — base app

Verified-only dating app for one city. Android (Kotlin + Compose) + Supabase.

## What's built

**Flow:** Phone OTP → profile setup → live selfie (in-app camera only) → liveness/ID verification → up to 10 daily picks → mutual like = match → chat expires in 7 days unless both agree to a meetup → post-meetup check-in feeds trust score.

**Enforced on the server (not just the app):**
- Unverified users can't see anyone, swipe, or view photos
- Users can't edit their own verification status or trust score (column grants)
- Birth date and gender are locked after signup
- 10 likes/passes per day (IST), no undo
- Matches only created by a trigger on mutual likes
- Messages only allowed in active matches; cron expires chats every 15 min
- Exact location never exposed: stored at ~1 km precision, only whole-km distance returned
- Blocks hide both users from each other everywhere
- Check-in "not who they claimed" → trust −20 and auto-report; "didn't feel safe" → auto-report

**App-side safety:** FLAG_SECURE (no screenshots), no gallery upload, report & block in chat, share-plan-with-a-friend.

## Setup

1. Create a Supabase project. Enable **Phone** auth (Auth → Providers) with an SMS provider (Twilio/MessageBird, or use test numbers during development).
2. Enable the `pg_cron` extension (Database → Extensions), then run `supabase/schema.sql` in the SQL editor.
3. Deploy functions:
   ```
   supabase functions deploy start-verification
   supabase functions deploy verification-webhook --no-verify-jwt
   supabase secrets set VERIFY_WEBHOOK_SECRET=<random-string>
   ```
4. Copy `local.properties.example` → `local.properties`, add your URL and anon key.
5. Open in Android Studio and run.

## Testing without a verification vendor

In the SQL editor, verify a test user manually:
```sql
update profiles set verification = 'verified' where id = '<user-uuid>';
```
Create 2–3 test accounts with Supabase test phone numbers to try matching, chat, and meetups end to end.

## TODO before beta
- [ ] Plug in the real vendor in `start-verification` and signature checks in `verification-webhook`
- [ ] Admin view for `reports` (Supabase dashboard is enough at first)
- [ ] Push notifications (FCM) for new match, message, meetup reply, check-in reminder
- [ ] Hide/deprioritise low-trust users in `get_daily_suggestions`
- [ ] More than one photo; periodic re-verification selfie
- [ ] Privacy policy, terms, grievance officer contact, account deletion screen
- [ ] Play Integrity API to block emulators and modified apps

Note: library versions were set when this was written; bump them in Android Studio if Gradle sync complains.
