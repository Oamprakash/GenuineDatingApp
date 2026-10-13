-- =====================================================================
-- Genuine — developer test tools
--
-- FOR DEVELOPMENT / TEST PROJECTS ONLY. Never run this on production.
--
-- Install: run this whole file once in the SQL Editor.
-- Use:     run one command at a time, e.g.  select dev.seed_users();
-- Remove:  select dev.uninstall();
--
-- Everything lives in a separate "dev" schema, which the app's API can't
-- reach, so none of these tools are callable from the app.
--
-- ---------------------------------------------------------------------
-- QUICK REFERENCE
--
-- Setup
--   select * from dev.settings;                              -- your test numbers / partner name
--   update dev.settings set value = '919999999902' where key = 'my_phone';
--   select * from dev.status();                              -- all test accounts at a glance
--
-- Verification
--   select dev.verify('919999999902');                       -- verify now, or pre-approve if not signed up yet
--   select dev.preverify('919999999903');                    -- pre-approve a number before it signs up
--
-- Seed users (fake verified profiles near you)
--   select dev.seed_users();                                 -- 12 women interested in men
--   select dev.seed_users(12, 'man', 'woman');               -- 12 men interested in women
--   select dev.delete_seeds();                               -- remove all seed users
--
-- Discovery
--   select dev.reset_discovery();                            -- fresh 10 swipes, 3 seeds pre-like you
--   select dev.make_seeds_like_me();                         -- every right-swipe on a seed = instant match
--
-- Matches
--   select dev.match_with_seeds();                           -- 4 matches in different states
--   select dev.match_partner();                              -- match you with your partner device (e.g. Kayadu)
--   select dev.set_chat_expiry(18);                          -- partner chat closes in 18 hours
--
-- Meetups (with your partner)
--   select dev.setup_upcoming_meetup(60);                    -- agreed meetup in 60 minutes
--   select dev.setup_past_meetup();                          -- happened 4 hours ago, check-in pending
--   select dev.setup_meet_again();                           -- both checked in, both want to meet again
--
-- Notifications
--   select dev.run_reminders();                              -- send reminders now (ignores quiet hours)
--   select dev.test_push();                                  -- test notification to your phone
--   select * from dev.push_log();                            -- last delivery results
--
-- Housekeeping
--   select dev.reset_trust();                                -- everyone back to trust score 50
--   select dev.clear_blocked_phones();                       -- undo account-deletion phone holds
--   select dev.uninstall();                                  -- remove all dev tools
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. Schema and settings
-- ---------------------------------------------------------------------
create schema if not exists dev;
revoke all on schema dev from public, anon, authenticated;

create table if not exists dev.settings (
  key   text primary key,
  value text not null
);
insert into dev.settings (key, value) values
  ('my_phone',     '919999999999'),  -- your main test account (country code, no "+")
  ('partner_name', 'Kayadu')         -- display name of the account on your second device
on conflict (key) do nothing;

create or replace function dev.setting(k text) returns text
language sql stable as $$ select value from dev.settings where key = k $$;

create or replace function dev.user_by_phone(p_phone text) returns uuid
language sql stable as $$ select id from auth.users where phone = p_phone $$;

create or replace function dev.me() returns uuid
language plpgsql stable as $$
declare v uuid := dev.user_by_phone(dev.setting('my_phone'));
begin
  if v is null then
    raise exception 'Test account % not found. Log in with it in the app first, or change dev.settings.',
      dev.setting('my_phone');
  end if;
  return v;
end $$;

create or replace function dev.partner() returns uuid
language plpgsql stable as $$
declare v uuid := (select id from public.profiles
                   where display_name ilike dev.setting('partner_name') limit 1);
begin
  if v is null then
    raise exception 'Partner "%" not found. Check the name in dev.settings.', dev.setting('partner_name');
  end if;
  return v;
end $$;

create or replace function dev.seed_ids() returns setof uuid
language sql stable as $$
  select id from auth.users where raw_app_meta_data->>'seed' = 'true'
$$;

create or replace function dev.match_between(a uuid, b uuid) returns uuid
language sql stable as $$
  select id from public.matches where user_a = least(a, b) and user_b = greatest(a, b)
$$;

create or replace function dev.partner_match() returns uuid
language plpgsql stable as $$
declare v uuid := dev.match_between(dev.me(), dev.partner());
begin
  if v is null then
    raise exception 'You and % are not matched. Run: select dev.match_partner();', dev.setting('partner_name');
  end if;
  return v;
end $$;


-- ---------------------------------------------------------------------
-- 1. Verification and pre-approved numbers
-- ---------------------------------------------------------------------
create table if not exists dev.preverified_phones (
  phone      text primary key,   -- country code, no "+"
  note       text,
  created_at timestamptz not null default now()
);

-- Move any numbers from the earlier public.preverified_phones table, then remove it
do $$
begin
  if to_regclass('public.preverified_phones') is not null then
    insert into dev.preverified_phones (phone, note, created_at)
    select phone, note, created_at from public.preverified_phones
    on conflict (phone) do nothing;
  end if;
end $$;
drop trigger if exists profiles_preverified on public.profiles;
drop table if exists public.preverified_phones;
drop function if exists public.apply_preverified();

-- New profiles with a pre-approved number are created as verified
create or replace function dev.apply_preverified() returns trigger
language plpgsql security definer set search_path = public as $$
declare ph text;
begin
  select phone into ph from auth.users where id = new.id;
  if ph is not null and exists (select 1 from dev.preverified_phones where phone = ph) then
    new.verification := 'verified';
  end if;
  return new;
end $$;

create trigger profiles_preverified before insert on public.profiles
  for each row execute function dev.apply_preverified();

create or replace function dev.preverify(p_phone text, p_note text default 'Test account') returns text
language plpgsql as $$
begin
  insert into dev.preverified_phones (phone, note) values (p_phone, p_note)
  on conflict (phone) do nothing;
  return 'Pre-approved ' || p_phone || '. It will be verified as soon as its profile is created.';
end $$;

-- Verifies now if the profile exists; otherwise pre-approves the number
create or replace function dev.verify(p_phone text) returns text
language plpgsql as $$
declare v uuid := dev.user_by_phone(p_phone);
begin
  if v is null or not exists (select 1 from public.profiles where id = v) then
    return dev.preverify(p_phone);
  end if;
  update public.profiles set verification = 'verified' where id = v;
  return 'Verified ' || p_phone || '. Bring the app back to the foreground to continue.';
end $$;


-- ---------------------------------------------------------------------
-- 2. Seed users
-- ---------------------------------------------------------------------
create or replace function dev.seed_users(
  p_count      int      default 12,
  p_gender     gender_t default 'woman',
  p_interested gender_t default 'man'
) returns text
language plpgsql as $$
declare
  women text[] := array['Priya','Divya','Meera','Anitha','Keerthana','Nithya',
                        'Swetha','Lakshmi','Harini','Janani','Sneha','Kavya'];
  men   text[] := array['Arjun','Karthik','Vikram','Rahul','Surya','Pranav',
                        'Aditya','Naveen','Gokul','Hari','Siddharth','Vishal'];
  bios  text[] := array[
    'Weekend baker. Will judge your filter coffee.',
    'Product designer. Beach walks at Besant Nagar.',
    'Carnatic music and bad puns.',
    'Runner, reader, occasional trekker.',
    'Software engineer who actually likes Mondays.',
    'Looking for someone to try new cafés with.',
    'Plant parent. 14 and counting.',
    'Teacher by day, board games by night.',
    'Photography walks around Mylapore.',
    'Learning to cook my grandmother''s recipes.',
    'Cyclist. OMR at 6am is underrated.',
    'Bookshop hopper and chai loyalist.'];
  interest_sets text[] := array[
    'Coffee,Reading,Trekking', 'Photography,Travel,Beach walks', 'Carnatic music,Cooking',
    'Running,Fitness,Yoga', 'Tech,Board games,Movies', 'Coffee,Art,Travel',
    'Pets,Cooking,Reading', 'Board games,Movies,Foodie', 'Photography,Cycling',
    'Cooking,Music,Dance', 'Cycling,Fitness,Coffee', 'Reading,Coffee,Travel'];
  base_lat double precision;
  base_lng double precision;
  uid      uuid;
  phone    text;
  created  int := 0;
  i        int;
begin
  -- Place seeds near your saved area (or central Chennai if you haven't set one)
  select pp.lat, pp.lng into base_lat, base_lng
  from public.profile_private pp where pp.id = dev.user_by_phone(dev.setting('my_phone'));
  base_lat := coalesce(base_lat, 12.95);
  base_lng := coalesce(base_lng, 80.20);

  for i in 1..p_count loop
    phone := '9190000' || lpad(i::text, 5, '0');           -- 919000000001, 919000000002, …
    continue when exists (select 1 from auth.users where auth.users.phone = phone);

    uid := gen_random_uuid();
    insert into auth.users (instance_id, id, aud, role, phone, phone_confirmed_at,
                            created_at, updated_at, confirmation_token, recovery_token,
                            email_change_token_new, email_change,
                            raw_app_meta_data, raw_user_meta_data)
    values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated',
            phone, now(), now(), now(), '', '', '', '',
            '{"provider":"phone","providers":["phone"],"seed":true}', '{}');

    insert into public.profiles (
      id, display_name, birth_date, gender, interested_in, bio, verification, trust_score, interests,
      relationship_goal, kids, height_cm, education, job_title, languages, hometown,
      drinking, smoking, exercise, diet, prompts)
    values (
      uid,
      (case when p_gender = 'man' then men else women end)[1 + (i - 1) % 12],
      (current_date - make_interval(years => 22 + (i % 12), days => i * 17))::date,
      p_gender, array[p_interested], bios[1 + (i - 1) % 12], 'verified', 50 + (i * 7) % 50,
      string_to_array(interest_sets[1 + (i - 1) % 12], ','),
      (array['long_term','long_open_short','figuring_out','friends'])[1 + i % 4],
      (array['want','open','dont_want'])[1 + i % 3],
      case when p_gender = 'man' then 168 + i % 15 else 152 + i % 18 end,
      (array['bachelors','masters','bachelors','doctorate'])[1 + i % 4],
      (array['Product designer','Software engineer','Teacher','Architect','Doctor','Marketing lead'])[1 + i % 6],
      '{Tamil,English}',
      (array['Chennai','Madurai','Coimbatore','Trichy'])[1 + i % 4],
      (array['never','socially'])[1 + i % 2],
      'never',
      (array['often','sometimes'])[1 + i % 2],
      (array['veg','nonveg','egg'])[1 + i % 3],
      jsonb_build_array(
        jsonb_build_object('q', 'first_meetup', 'a', 'Filter coffee and a slow walk along Besant Nagar beach.'),
        jsonb_build_object('q', 'green_flag',   'a', 'Someone who shows up on time and actually asks questions back.'))
    );

    insert into public.profile_private (id, lat, lng, min_age, max_age, max_distance_km)
    values (uid,
            round((base_lat + (random() - 0.5) * 0.06)::numeric, 2),
            round((base_lng + (random() - 0.5) * 0.06)::numeric, 2),
            18, 60, 10);

    created := created + 1;
  end loop;

  return 'Created ' || created || ' seed users (' || p_gender || 's interested in ' || p_interested || 's).';
end $$;

create or replace function dev.delete_seeds() returns text
language plpgsql as $$
declare n int;
begin
  delete from public.reports
  where reported_id in (select dev.seed_ids()) or reporter_id in (select dev.seed_ids());
  delete from auth.users where raw_app_meta_data->>'seed' = 'true';
  get diagnostics n = row_count;
  return 'Deleted ' || n || ' seed users and all their data.';
end $$;


-- ---------------------------------------------------------------------
-- 3. Discovery
-- ---------------------------------------------------------------------

-- Fresh start: clears your swipes/blocks/seed matches, puts seeds near you,
-- and makes p_prelikes seeds like you (liking them back = instant match)
create or replace function dev.reset_discovery(p_prelikes int default 3) returns text
language plpgsql as $$
declare
  me     uuid := dev.me();
  my_lat double precision;
  my_lng double precision;
begin
  if not exists (select 1 from dev.seed_ids()) then
    raise exception 'No seed users. Run: select dev.seed_users();';
  end if;

  select lat, lng into my_lat, my_lng from public.profile_private where id = me;
  if my_lat is null then
    my_lat := 12.95; my_lng := 80.20;
    update public.profile_private set lat = my_lat, lng = my_lng where id = me;
  end if;

  delete from public.swipes where swiper = me;
  delete from public.blocks where blocker = me;
  delete from public.matches m
  where (m.user_a = me and m.user_b in (select dev.seed_ids()))
     or (m.user_b = me and m.user_a in (select dev.seed_ids()));

  update public.profiles set verification = 'verified' where id in (select dev.seed_ids());
  update public.profile_private
     set lat = round((my_lat + (random() - 0.5) * 0.06)::numeric, 2),
         lng = round((my_lng + (random() - 0.5) * 0.06)::numeric, 2),
         min_age = 18, max_age = 60, max_distance_km = 10, paused = false
   where id in (select dev.seed_ids());

  update public.profiles set verification = 'verified' where id = me;
  update public.profile_private set min_age = 18, max_age = 60, paused = false where id = me;

  alter table public.swipes disable trigger swipes_cap;
  delete from public.swipes where target = me and swiper in (select dev.seed_ids());
  insert into public.swipes (swiper, target, liked)
  select u.id, me, true from auth.users u
  where u.raw_app_meta_data->>'seed' = 'true'
  order by u.phone
  limit p_prelikes;
  alter table public.swipes enable trigger swipes_cap;

  return 'Discovery reset. Pull down on Today. ' || p_prelikes || ' seed users already like you.';
end $$;

create or replace function dev.make_seeds_like_me() returns text
language plpgsql as $$
declare me uuid := dev.me(); n int;
begin
  alter table public.swipes disable trigger swipes_cap;
  insert into public.swipes (swiper, target, liked)
  select u.id, me, true from auth.users u
  where u.raw_app_meta_data->>'seed' = 'true'
    and not exists (select 1 from public.swipes x where x.swiper = me and x.target = u.id)
  on conflict (swiper, target) do update set liked = true;
  get diagnostics n = row_count;
  alter table public.swipes enable trigger swipes_cap;
  return n || ' seed users now like you. Any right-swipe on them creates a match.';
end $$;


-- ---------------------------------------------------------------------
-- 4. Matches
-- ---------------------------------------------------------------------

-- Up to 4 matches with seeds, each in a different state:
-- 1 brand new · 2 active chat · 3 closes in 5 hours · 4 meetup agreed for tomorrow
create or replace function dev.match_with_seeds(p_count int default 4) returns text
language plpgsql as $$
declare
  me    uuid := dev.me();
  seeds uuid[];
  s     uuid;
  mid   uuid;
  i     int := 0;
begin
  seeds := array(
    select u.id from auth.users u
    where u.raw_app_meta_data->>'seed' = 'true'
      and not exists (select 1 from public.matches m
                      where m.status in ('active','meetup_planned')
                        and ((m.user_a = me and m.user_b = u.id) or (m.user_a = u.id and m.user_b = me)))
      and not exists (select 1 from public.blocks b
                      where (b.blocker = me and b.blocked = u.id) or (b.blocker = u.id and b.blocked = me))
    order by u.phone
    limit p_count);
  if cardinality(seeds) = 0 then
    raise exception 'No available seed users. Run dev.reset_discovery() or dev.seed_users() first.';
  end if;

  alter table public.swipes disable trigger swipes_cap;

  foreach s in array seeds loop
    i := i + 1;

    insert into public.swipes (swiper, target, liked) values (s, me, true)
      on conflict (swiper, target) do update set liked = true;
    insert into public.swipes (swiper, target, liked) values (me, s, true)
      on conflict (swiper, target) do update set liked = true;

    insert into public.matches (user_a, user_b) values (least(me, s), greatest(me, s))
    on conflict (user_a, user_b) do update
      set status = 'active', chat_expires_at = now() + interval '7 days', expiry_reminded_for = null
    returning id into mid;

    delete from public.messages where match_id = mid;
    delete from public.meetups  where match_id = mid;
    insert into public.match_reads (match_id, user_id, last_read_id) values (mid, me, 0)
    on conflict (match_id, user_id) do update set last_read_id = 0;

    if i % 4 = 2 then
      update public.matches set chat_expires_at = now() + interval '5 days' where id = mid;
      insert into public.messages (match_id, sender_id, body, created_at) values
        (mid, s,  'Hi! I saw you like coffee too.',        now() - interval '3 hours'),
        (mid, me, 'Hey! Yes, filter coffee is a must.',    now() - interval '2 hours'),
        (mid, s,  'Know any good cafés around Velachery?', now() - interval '1 hour');

    elsif i % 4 = 3 then
      update public.matches set chat_expires_at = now() + interval '5 hours' where id = mid;
      insert into public.messages (match_id, sender_id, body, created_at) values
        (mid, s, 'Are you free this weekend?', now() - interval '30 minutes');

    elsif i % 4 = 0 then
      insert into public.messages (match_id, sender_id, body, created_at) values
        (mid, me, 'How about coffee tomorrow evening?', now() - interval '3 hours'),
        (mid, s,  'Sounds good, see you there!',         now() - interval '2 hours');
      insert into public.meetups (match_id, proposed_by, venue_name, scheduled_at, status, created_at)
      values (mid, me, 'Amethyst Café, Royapettah',
              date_trunc('hour', now()) + interval '1 day', 'accepted', now() - interval '2 hours');
      update public.matches set status = 'meetup_planned', chat_expires_at = null where id = mid;
    end if;
  end loop;

  alter table public.swipes enable trigger swipes_cap;
  return 'Created ' || cardinality(seeds) || ' matches with seed users.';
end $$;

-- Match (or re-open a match) between you and your partner device
create or replace function dev.match_partner() returns text
language plpgsql as $$
declare me uuid := dev.me(); p uuid := dev.partner();
begin
  update public.profiles set verification = 'verified' where id in (me, p);
  delete from public.blocks where (blocker = me and blocked = p) or (blocker = p and blocked = me);

  alter table public.swipes disable trigger swipes_cap;
  insert into public.swipes (swiper, target, liked) values (me, p, true)
    on conflict (swiper, target) do update set liked = true;
  insert into public.swipes (swiper, target, liked) values (p, me, true)
    on conflict (swiper, target) do update set liked = true;
  alter table public.swipes enable trigger swipes_cap;

  insert into public.matches (user_a, user_b) values (least(me, p), greatest(me, p))
  on conflict (user_a, user_b) do update
    set status = 'active', chat_expires_at = now() + interval '7 days', expiry_reminded_for = null;

  return 'Matched with ' || dev.setting('partner_name') || '.';
end $$;

-- Partner chat closes in p_hours (and the "closing soon" reminder can fire again)
create or replace function dev.set_chat_expiry(p_hours numeric default 18) returns text
language plpgsql as $$
declare mid uuid := dev.partner_match();
begin
  update public.matches
     set status = 'active',
         chat_expires_at = now() + make_interval(secs => p_hours * 3600),
         expiry_reminded_for = null
   where id = mid;
  return 'Chat with ' || dev.setting('partner_name') || ' now closes in ' || p_hours || ' hours.';
end $$;


-- ---------------------------------------------------------------------
-- 5. Meetups (with your partner)
-- ---------------------------------------------------------------------

-- Makes the latest meetup the only active one: closes pending plans and
-- moves older meetups back, so the chat banner shows the test meetup
create or replace function dev.prepare_latest_meetup(p_scheduled timestamptz) returns uuid
language plpgsql as $$
declare
  mid uuid := dev.partner_match();
  tid uuid;
begin
  select id into tid from public.meetups where match_id = mid order by created_at desc limit 1;

  if tid is null then
    insert into public.meetups (match_id, proposed_by, venue_name, scheduled_at, status, created_at)
    values (mid, dev.me(), 'Amethyst Café, Royapettah', p_scheduled, 'accepted',
            least(now(), p_scheduled) - interval '2 days')
    returning id into tid;
  else
    update public.meetups set status = 'cancelled'
    where match_id = mid and id <> tid and status = 'proposed';
    update public.meetups
       set created_at = created_at - interval '10 days',
           scheduled_at = scheduled_at - interval '10 days'
     where match_id = mid and id <> tid;
    update public.meetups
       set status = 'accepted',
           created_at = least(now(), p_scheduled) - interval '2 days',
           scheduled_at = p_scheduled,
           upcoming_reminded_at = null,
           checkin_reminded_at = null
     where id = tid;
  end if;

  update public.matches set status = 'meetup_planned', chat_expires_at = null where id = mid;
  delete from public.checkins where meetup_id = tid;
  return tid;
end $$;

create or replace function dev.setup_upcoming_meetup(p_minutes int default 60) returns text
language plpgsql as $$
begin
  perform dev.prepare_latest_meetup(now() + make_interval(mins => p_minutes));
  return 'Agreed meetup in ' || p_minutes || ' minutes. Run dev.run_reminders() for the "meetup soon" reminder.';
end $$;

create or replace function dev.setup_past_meetup(p_hours_ago int default 4) returns text
language plpgsql as $$
begin
  perform dev.prepare_latest_meetup(now() - make_interval(hours => p_hours_ago));
  return 'Meetup happened ' || p_hours_ago || ' hours ago. Open the chat to check in, or run dev.run_reminders().';
end $$;

-- Both checked in and both want to meet again (two inserts, so the
-- "You both want to meet again!" notification is sent once)
create or replace function dev.setup_meet_again() returns text
language plpgsql as $$
declare tid uuid := dev.prepare_latest_meetup(now() - interval '4 hours');
begin
  insert into public.checkins (meetup_id, user_id, did_meet, profile_accurate, felt_safe, want_again)
  values (tid, dev.me(), true, true, true, true);
  insert into public.checkins (meetup_id, user_id, did_meet, profile_accurate, felt_safe, want_again)
  values (tid, dev.partner(), true, true, true, true);
  return 'Both checked in and want to meet again. The chat now shows "Plan next".';
end $$;


-- ---------------------------------------------------------------------
-- 6. Notifications
-- ---------------------------------------------------------------------
create or replace function dev.run_reminders() returns text
language plpgsql as $$
begin
  perform public.send_reminders(true);
  return 'Reminders sent (quiet hours ignored). Check: select * from dev.push_log();';
end $$;

create or replace function dev.test_push(p_phone text default null) returns text
language plpgsql as $$
declare target uuid := coalesce(dev.user_by_phone(p_phone), dev.me());
begin
  if not exists (select 1 from public.device_tokens where user_id = target) then
    return 'No registered device for this account. Open the app once so it reaches Home.';
  end if;
  perform public.notify_user(target, 'Test', 'Hello from Genuine', '{"type": "message"}'::jsonb);
  return 'Sent. Check: select * from dev.push_log();';
end $$;

-- Last few responses from the push Edge Function
-- (200 ok = delivered to Google · no devices = phone not registered · 403 = push secrets differ)
create or replace function dev.push_log(p_limit int default 5)
returns table (created timestamptz, status_code int, content text)
language sql stable as $$
  select r.created, r.status_code, r.content::text
  from net._http_response r
  order by r.created desc
  limit p_limit
$$;


-- ---------------------------------------------------------------------
-- 7. Status and housekeeping
-- ---------------------------------------------------------------------
create or replace function dev.status()
returns table (phone text, name text, verification text, paused boolean,
               seed boolean, device_registered boolean, trust_score int)
language sql stable as $$
  select u.phone::text, p.display_name, p.verification::text, pp.paused,
         coalesce(u.raw_app_meta_data->>'seed' = 'true', false),
         exists (select 1 from public.device_tokens d where d.user_id = u.id),
         p.trust_score
  from auth.users u
  left join public.profiles p on p.id = u.id
  left join public.profile_private pp on pp.id = u.id
  order by (u.raw_app_meta_data->>'seed' = 'true') nulls first, u.phone
$$;

create or replace function dev.reset_trust() returns text
language plpgsql as $$
begin
  update public.profiles set trust_score = 50;
  return 'All trust scores reset to 50.';
end $$;

create or replace function dev.clear_blocked_phones() returns text
language plpgsql as $$
declare n int;
begin
  delete from public.blocked_phones;
  get diagnostics n = row_count;
  return 'Removed ' || n || ' phone holds. Those numbers can sign up again.';
end $$;

-- Removes every dev tool, including the pre-approval trigger on profiles
create or replace function dev.uninstall() returns text
language plpgsql as $$
begin
  drop trigger if exists profiles_preverified on public.profiles;
  drop schema dev cascade;
  return 'Dev tools removed.';
end $$;
