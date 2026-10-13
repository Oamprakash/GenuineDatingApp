-- =====================================================================
-- Genuine — base schema for Supabase (Postgres)
-- Run in the Supabase SQL editor on a fresh project.
-- Principles: verified-only visibility, server-enforced limits,
-- exact location never exposed, chats expire unless a meetup is agreed.
-- =====================================================================
create extension if not exists pg_cron;

create type gender_t        as enum ('man','woman','nonbinary');
create type verification_t  as enum ('pending','in_review','verified','rejected');
create type match_status_t  as enum ('active','meetup_planned','expired','unmatched');
create type meetup_status_t as enum ('proposed','accepted','declined','cancelled');

-- ---------- Tables ----------------------------------------------------
-- Public profile: visible only to other verified users
create table public.profiles (
  id             uuid primary key references auth.users on delete cascade,
  display_name   text not null check (char_length(display_name) between 2 and 30),
  birth_date     date not null check (birth_date <= current_date - interval '18 years'),
  gender         gender_t not null,
  interested_in  gender_t[] not null check (cardinality(interested_in) > 0),
  bio            text check (char_length(bio) <= 300),
  zone           text not null default 'omr-velachery',
  verification   verification_t not null default 'pending',
  trust_score    int not null default 50 check (trust_score between 0 and 100),
  created_at     timestamptz not null default now()
);

-- Private settings: owner only. The app stores location rounded to ~1 km.
create table public.profile_private (
  id               uuid primary key references public.profiles on delete cascade,
  lat              double precision,
  lng              double precision,
  min_age          int not null default 21 check (min_age >= 18),
  max_age          int not null default 35,
  max_distance_km  int not null default 10 check (max_distance_km between 1 and 50),
  updated_at       timestamptz not null default now()
);

create table public.photos (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null default auth.uid() references public.profiles on delete cascade,
  storage_path  text not null,
  position      int not null default 0,
  created_at    timestamptz not null default now()
);

create table public.swipes (
  swiper      uuid not null default auth.uid() references public.profiles on delete cascade,
  target      uuid not null references public.profiles on delete cascade,
  liked       boolean not null,
  created_at  timestamptz not null default now(),
  primary key (swiper, target),
  check (swiper <> target)
);

create table public.matches (
  id               uuid primary key default gen_random_uuid(),
  user_a           uuid not null references public.profiles on delete cascade,
  user_b           uuid not null references public.profiles on delete cascade,
  status           match_status_t not null default 'active',
  chat_expires_at  timestamptz default (now() + interval '7 days'),
  created_at       timestamptz not null default now(),
  unique (user_a, user_b),
  check (user_a < user_b)
);

create table public.messages (
  id          bigint generated always as identity primary key,
  match_id    uuid not null references public.matches on delete cascade,
  sender_id   uuid not null default auth.uid() references public.profiles,
  body        text not null check (char_length(body) between 1 and 1000),
  created_at  timestamptz not null default now()
);
create index on public.messages (match_id, created_at);

create table public.meetups (
  id            uuid primary key default gen_random_uuid(),
  match_id      uuid not null references public.matches on delete cascade,
  proposed_by   uuid not null default auth.uid() references public.profiles,
  venue_name    text not null,
  venue_lat     double precision,
  venue_lng     double precision,
  scheduled_at  timestamptz not null,
  status        meetup_status_t not null default 'proposed',
  created_at    timestamptz not null default now(),
  check (scheduled_at > created_at)
);

create table public.checkins (
  meetup_id         uuid not null references public.meetups on delete cascade,
  user_id           uuid not null default auth.uid() references public.profiles,
  did_meet          boolean not null,
  profile_accurate  boolean,          -- "Were they who they claimed to be?"
  felt_safe         boolean,
  created_at        timestamptz not null default now(),
  primary key (meetup_id, user_id)
);

create table public.reports (
  id           uuid primary key default gen_random_uuid(),
  reporter_id  uuid not null default auth.uid() references public.profiles,
  reported_id  uuid not null references public.profiles,
  reason       text not null,   -- fake_profile | harassment | no_show | unsafe | other
  details      text,
  status       text not null default 'open',
  created_at   timestamptz not null default now()
);

create table public.blocks (
  blocker     uuid not null default auth.uid() references public.profiles on delete cascade,
  blocked     uuid not null references public.profiles on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (blocker, blocked)
);

-- ---------- Column-level write protection -----------------------------
-- Users can never set their own verification status or trust score.
-- birth_date and gender are locked after signup (changes go via support).
revoke insert, update on public.profiles from authenticated;
grant insert (id, display_name, birth_date, gender, interested_in, bio, zone) on public.profiles to authenticated;
grant update (display_name, interested_in, bio) on public.profiles to authenticated;

revoke insert, update, delete on public.matches  from authenticated; -- created by trigger only
revoke update, delete         on public.messages from authenticated;
revoke update                 on public.meetups  from authenticated; -- respond via RPC
revoke update, delete         on public.swipes   from authenticated; -- no undo
revoke update, delete         on public.checkins from authenticated;
revoke update, delete         on public.reports  from authenticated;

-- ---------- Helpers ----------------------------------------------------
create or replace function public.is_verified() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from profiles where id = auth.uid() and verification = 'verified');
$$;

create or replace function public.is_match_member(m uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from matches where id = m and auth.uid() in (user_a, user_b));
$$;

create or replace function public.is_blocked_pair(other uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from blocks
                 where (blocker = auth.uid() and blocked = other)
                    or (blocker = other and blocked = auth.uid()));
$$;

-- ---------- Row Level Security ----------------------------------------
alter table public.profiles        enable row level security;
alter table public.profile_private enable row level security;
alter table public.photos          enable row level security;
alter table public.swipes          enable row level security;
alter table public.matches         enable row level security;
alter table public.messages        enable row level security;
alter table public.meetups         enable row level security;
alter table public.checkins        enable row level security;
alter table public.reports         enable row level security;
alter table public.blocks          enable row level security;

create policy profiles_read on public.profiles for select to authenticated
  using (id = auth.uid() or (is_verified() and verification = 'verified' and not is_blocked_pair(id)));
create policy profiles_insert on public.profiles for insert to authenticated with check (id = auth.uid());
create policy profiles_update on public.profiles for update to authenticated using (id = auth.uid());

create policy private_owner on public.profile_private for all to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

create policy photos_read on public.photos for select to authenticated
  using (user_id = auth.uid() or (is_verified() and not is_blocked_pair(user_id)));
create policy photos_insert on public.photos for insert to authenticated with check (user_id = auth.uid());
create policy photos_delete on public.photos for delete to authenticated using (user_id = auth.uid());

create policy swipes_insert on public.swipes for insert to authenticated
  with check (swiper = auth.uid() and is_verified());
create policy swipes_read on public.swipes for select to authenticated using (swiper = auth.uid());

create policy matches_read on public.matches for select to authenticated using (is_match_member(id));

create policy messages_read on public.messages for select to authenticated using (is_match_member(match_id));
create policy messages_insert on public.messages for insert to authenticated
  with check (sender_id = auth.uid() and exists (
    select 1 from matches m where m.id = match_id
      and auth.uid() in (m.user_a, m.user_b)
      and m.status in ('active','meetup_planned')));

create policy meetups_read on public.meetups for select to authenticated using (is_match_member(match_id));
create policy meetups_insert on public.meetups for insert to authenticated
  with check (proposed_by = auth.uid() and exists (
    select 1 from matches m where m.id = match_id
      and auth.uid() in (m.user_a, m.user_b) and m.status = 'active'));

create policy checkins_insert on public.checkins for insert to authenticated
  with check (user_id = auth.uid() and exists (
    select 1 from meetups mt where mt.id = meetup_id and mt.status = 'accepted'
      and mt.scheduled_at < now() and is_match_member(mt.match_id)));
create policy checkins_read on public.checkins for select to authenticated using (user_id = auth.uid());

create policy reports_insert on public.reports for insert to authenticated with check (reporter_id = auth.uid());
create policy blocks_owner on public.blocks for all to authenticated
  using (blocker = auth.uid()) with check (blocker = auth.uid());

-- ---------- Triggers ---------------------------------------------------
-- Daily like/pass cap (server-enforced, IST day boundary)
create or replace function public.enforce_daily_swipes() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from swipes
       where swiper = new.swiper
         and created_at >= (date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata')) >= 10 then
    raise exception 'Daily limit reached. New profiles tomorrow.';
  end if;
  return new;
end $$;
create trigger swipes_cap before insert on public.swipes
  for each row execute function public.enforce_daily_swipes();

-- Mutual like -> match (chat opens with a 7-day clock)
create or replace function public.on_swipe() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.liked and exists (select 1 from swipes
                           where swiper = new.target and target = new.swiper and liked) then
    insert into matches (user_a, user_b)
    values (least(new.swiper, new.target), greatest(new.swiper, new.target))
    on conflict do nothing;
  end if;
  return new;
end $$;
create trigger swipes_match after insert on public.swipes
  for each row execute function public.on_swipe();

-- Post-date check-in feeds the trust score and auto-files reports
create or replace function public.on_checkin() returns trigger
language plpgsql security definer set search_path = public as $$
declare other uuid;
begin
  select case when m.user_a = new.user_id then m.user_b else m.user_a end into other
  from meetups mt join matches m on m.id = mt.match_id where mt.id = new.meetup_id;

  if new.did_meet and new.profile_accurate is false then
    update profiles set trust_score = greatest(0, trust_score - 20) where id = other;
    insert into reports (reporter_id, reported_id, reason, details)
    values (new.user_id, other, 'fake_profile', 'Auto: check-in said profile was not accurate');
  elsif new.did_meet and new.profile_accurate then
    update profiles set trust_score = least(100, trust_score + 5) where id = other;
  end if;

  if new.felt_safe is false then
    insert into reports (reporter_id, reported_id, reason, details)
    values (new.user_id, other, 'unsafe', 'Auto: check-in said user did not feel safe');
  end if;
  return new;
end $$;
create trigger checkins_trust after insert on public.checkins
  for each row execute function public.on_checkin();

-- ---------- RPCs -------------------------------------------------------
-- Today's suggestions: verified, mutual preferences, within distance,
-- not seen, not blocked. Distance rounded to whole km (no triangulation).
create or replace function public.get_daily_suggestions()
returns table (id uuid, display_name text, age int, bio text, distance_km int, photo_path text)
language sql stable security definer set search_path = public as $$
  with me as (
    select p.id, p.gender, p.interested_in, pp.lat, pp.lng,
           pp.min_age, pp.max_age, pp.max_distance_km
    from profiles p join profile_private pp on pp.id = p.id
    where p.id = auth.uid() and p.verification = 'verified'
  ),
  used as (
    select count(*)::int as n from swipes
    where swiper = auth.uid()
      and created_at >= (date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata')
  ),
  cand as (
    select o.id, o.display_name, o.bio, o.trust_score,
           date_part('year', age(o.birth_date))::int as age,
           round(6371 * acos(least(1.0,
              cos(radians(me.lat)) * cos(radians(op.lat)) * cos(radians(op.lng) - radians(me.lng))
            + sin(radians(me.lat)) * sin(radians(op.lat)))))::int as distance_km,
           me.max_distance_km, me.min_age, me.max_age
    from me
    join profiles o on o.id <> me.id and o.verification = 'verified'
    join profile_private op on op.id = o.id
    where o.gender = any(me.interested_in)
      and me.gender = any(o.interested_in)
      and op.lat is not null and me.lat is not null
      and not exists (select 1 from swipes s where s.swiper = me.id and s.target = o.id)
      and not exists (select 1 from blocks b
                      where (b.blocker = me.id and b.blocked = o.id)
                         or (b.blocker = o.id and b.blocked = me.id))
  )
  select c.id, c.display_name, c.age, c.bio, c.distance_km,
         (select ph.storage_path from photos ph where ph.user_id = c.id
          order by ph.position limit 1)
  from cand c, used
  where c.distance_km <= c.max_distance_km
    and c.age between c.min_age and c.max_age
  order by c.trust_score desc, random()
  limit greatest(0, 10 - (select n from used));
$$;

create or replace function public.get_my_matches()
returns table (match_id uuid, other_id uuid, other_name text, other_photo text,
               status match_status_t, chat_expires_at timestamptz,
               last_message text, last_at timestamptz)
language sql stable security definer set search_path = public as $$
  select m.id, o.id, o.display_name,
         (select ph.storage_path from photos ph where ph.user_id = o.id order by ph.position limit 1),
         m.status, m.chat_expires_at, lm.body, lm.created_at
  from matches m
  join profiles o on o.id = case when m.user_a = auth.uid() then m.user_b else m.user_a end
  left join lateral (select body, created_at from messages
                     where match_id = m.id order by created_at desc limit 1) lm on true
  where auth.uid() in (m.user_a, m.user_b) and m.status in ('active','meetup_planned')
  order by coalesce(lm.created_at, m.created_at) desc;
$$;

create or replace function public.respond_meetup(p_meetup_id uuid, p_accept boolean)
returns void language plpgsql security definer set search_path = public as $$
declare mt meetups;
begin
  select * into mt from meetups where id = p_meetup_id for update;
  if not found or not is_match_member(mt.match_id) then raise exception 'Not allowed'; end if;
  if mt.proposed_by = auth.uid() then raise exception 'You cannot answer your own proposal'; end if;
  if mt.status <> 'proposed' then raise exception 'Already answered'; end if;

  update meetups
     set status = (case when p_accept then 'accepted' else 'declined' end)::meetup_status_t
   where id = p_meetup_id;

  if p_accept then  -- agreed to meet: the chat stops expiring
    update matches set status = 'meetup_planned', chat_expires_at = null where id = mt.match_id;
  end if;
end $$;

create or replace function public.unmatch(p_match_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not is_match_member(p_match_id) then raise exception 'Not allowed'; end if;
  update matches set status = 'unmatched' where id = p_match_id;
end $$;

-- ---------- Scheduled jobs ---------------------------------------------
select cron.schedule('expire-chats', '*/15 * * * *', $$
  update public.matches set status = 'expired'
  where status = 'active' and chat_expires_at < now();
$$);

-- ---------- Realtime ---------------------------------------------------
alter publication supabase_realtime add table public.messages, public.meetups;

-- ---------- Storage ----------------------------------------------------
insert into storage.buckets (id, name, public) values ('photos', 'photos', false)
on conflict (id) do nothing;

create policy "upload own photos" on storage.objects for insert to authenticated
  with check (bucket_id = 'photos' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "delete own photos" on storage.objects for delete to authenticated
  using (bucket_id = 'photos' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "verified users view photos" on storage.objects for select to authenticated
  using (bucket_id = 'photos' and (
    (storage.foldername(name))[1] = auth.uid()::text or public.is_verified()));

-- Table access for logged-in users (RLS still filters rows)
grant usage on schema public to authenticated;

grant select on public.profiles to authenticated;          -- insert/update already granted per-column
grant select, insert, update, delete on public.profile_private to authenticated;
grant select, insert, delete on public.photos   to authenticated;
grant select, insert         on public.swipes   to authenticated;
grant select                 on public.matches  to authenticated;
grant select, insert         on public.messages to authenticated;
grant select, insert         on public.meetups  to authenticated;
grant select, insert         on public.checkins to authenticated;
grant insert                 on public.reports  to authenticated;
grant select, insert, delete on public.blocks   to authenticated;

grant usage, select on all sequences in schema public to authenticated;

-- RPCs the app calls
grant execute on function public.get_daily_suggestions()             to authenticated;
grant execute on function public.get_my_matches()                    to authenticated;
grant execute on function public.respond_meetup(uuid, boolean)       to authenticated;
grant execute on function public.unmatch(uuid)                       to authenticated;
grant execute on function public.is_verified()                       to authenticated;
grant execute on function public.is_match_member(uuid)               to authenticated;
grant execute on function public.is_blocked_pair(uuid)               to authenticated;

alter table public.meetups
  add column if not exists cancelled_by uuid references public.profiles,
  add column if not exists cancelled_at timestamptz;

create or replace function public.cancel_meetup(p_meetup_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare mt meetups;
begin
  select * into mt from meetups where id = p_meetup_id for update;
  if not found or not is_match_member(mt.match_id) then raise exception 'Not allowed'; end if;
  if mt.status not in ('proposed','accepted') then raise exception 'This meetup is no longer active'; end if;
  if mt.status = 'proposed' and mt.proposed_by <> auth.uid() then raise exception 'Decline it instead'; end if;
  if mt.scheduled_at <= now() then raise exception 'This meetup time has already passed'; end if;

  update meetups set status = 'cancelled', cancelled_by = auth.uid(), cancelled_at = now()
   where id = p_meetup_id;

  update matches
     set status = 'active',
         chat_expires_at = greatest(coalesce(chat_expires_at, now()), now() + interval '3 days')
   where id = mt.match_id and status in ('active','meetup_planned');

  insert into messages (match_id, sender_id, body)
  values (mt.match_id, auth.uid(), 'Cancelled the meetup at ' || mt.venue_name || '.');
end $$;

create or replace function public.reschedule_meetup(p_meetup_id uuid, p_venue text, p_scheduled_at timestamptz)
returns uuid language plpgsql security definer set search_path = public as $$
declare mt meetups; new_id uuid;
begin
  select * into mt from meetups where id = p_meetup_id for update;
  if not found or not is_match_member(mt.match_id) then raise exception 'Not allowed'; end if;
  if mt.status not in ('proposed','accepted') then raise exception 'This meetup is no longer active'; end if;
  if mt.scheduled_at <= now() then raise exception 'This meetup time has already passed'; end if;
  if p_scheduled_at <= now() then raise exception 'Pick a time later than now'; end if;
  if char_length(trim(p_venue)) = 0 then raise exception 'Enter a place'; end if;

  update meetups set status = 'cancelled', cancelled_by = auth.uid(), cancelled_at = now()
   where id = p_meetup_id;

  insert into meetups (match_id, proposed_by, venue_name, scheduled_at)
  values (mt.match_id, auth.uid(), trim(p_venue), p_scheduled_at)
  returning id into new_id;

  update matches
     set status = 'active',
         chat_expires_at = greatest(coalesce(chat_expires_at, now()), now() + interval '3 days')
   where id = mt.match_id and status in ('active','meetup_planned');

  insert into messages (match_id, sender_id, body)
  values (mt.match_id, auth.uid(),
          'Suggested a new plan: ' || trim(p_venue) || ', ' ||
          to_char(p_scheduled_at at time zone 'Asia/Kolkata', 'Dy DD Mon, HH12:MI AM') || '.');
  return new_id;
end $$;

grant execute on function public.cancel_meetup(uuid) to authenticated;
grant execute on function public.reschedule_meetup(uuid, text, timestamptz) to authenticated;

-- Messages, meetups and check-ins go with the account
alter table public.messages
  drop constraint messages_sender_id_fkey,
  add constraint messages_sender_id_fkey
    foreign key (sender_id) references public.profiles(id) on delete cascade;

alter table public.meetups
  drop constraint meetups_proposed_by_fkey,
  add constraint meetups_proposed_by_fkey
    foreign key (proposed_by) references public.profiles(id) on delete cascade;

alter table public.meetups
  drop constraint if exists meetups_cancelled_by_fkey,
  add constraint meetups_cancelled_by_fkey
    foreign key (cancelled_by) references public.profiles(id) on delete set null;

alter table public.checkins
  drop constraint checkins_user_id_fkey,
  add constraint checkins_user_id_fkey
    foreign key (user_id) references public.profiles(id) on delete cascade;

-- Reports are kept for moderation, but lose the link to a deleted account
alter table public.reports
  alter column reporter_id drop not null,
  alter column reported_id drop not null;

alter table public.reports
  drop constraint reports_reporter_id_fkey,
  add constraint reports_reporter_id_fkey
    foreign key (reporter_id) references public.profiles(id) on delete set null;

alter table public.reports
  drop constraint reports_reported_id_fkey,
  add constraint reports_reported_id_fkey
    foreign key (reported_id) references public.profiles(id) on delete set null;

-- Interests: up to 5 tags, editable by the owner
alter table public.profiles
  add column if not exists interests text[] not null default '{}'
  constraint profiles_interests_max check (cardinality(interests) <= 5);
grant insert (interests), update (interests) on public.profiles to authenticated;

-- Pause (used in step 3; added now so the suggestions function only changes once)
alter table public.profile_private
  add column if not exists paused boolean not null default false;

-- Suggestions now return interests and skip paused profiles.
-- The return type changes, so the function must be dropped and recreated.
drop function if exists public.get_daily_suggestions();

create function public.get_daily_suggestions()
returns table (id uuid, display_name text, age int, bio text, distance_km int,
               photo_path text, interests text[])
language sql stable security definer set search_path = public as $$
  with me as (
    select p.id, p.gender, p.interested_in, pp.lat, pp.lng,
           pp.min_age, pp.max_age, pp.max_distance_km
    from profiles p join profile_private pp on pp.id = p.id
    where p.id = auth.uid() and p.verification = 'verified'
  ),
  used as (
    select count(*)::int as n from swipes
    where swiper = auth.uid()
      and created_at >= (date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata')
  ),
  cand as (
    select o.id, o.display_name, o.bio, o.trust_score, o.interests,
           date_part('year', age(o.birth_date))::int as age,
           round(6371 * acos(least(1.0,
              cos(radians(me.lat)) * cos(radians(op.lat)) * cos(radians(op.lng) - radians(me.lng))
            + sin(radians(me.lat)) * sin(radians(op.lat)))))::int as distance_km,
           me.max_distance_km, me.min_age, me.max_age
    from me
    join profiles o on o.id <> me.id and o.verification = 'verified'
    join profile_private op on op.id = o.id
    where o.gender = any(me.interested_in)
      and me.gender = any(o.interested_in)
      and not op.paused
      and op.lat is not null and me.lat is not null
      and not exists (select 1 from swipes s where s.swiper = me.id and s.target = o.id)
      and not exists (select 1 from blocks b
                      where (b.blocker = me.id and b.blocked = o.id)
                         or (b.blocker = o.id and b.blocked = me.id))
  )
  select c.id, c.display_name, c.age, c.bio, c.distance_km,
         (select ph.storage_path from photos ph where ph.user_id = c.id
          order by ph.position limit 1),
         c.interests
  from cand c, used
  where c.distance_km <= c.max_distance_km
    and c.age between c.min_age and c.max_age
  order by c.trust_score desc, random()
  limit greatest(0, 10 - (select n from used));
$$;

grant execute on function public.get_daily_suggestions() to authenticated;

-- Optional: give seed users some interests so the tags show in Discovery
update public.profiles set interests = (array[
  '{Coffee,Reading,Trekking}', '{Photography,Travel,Beach walks}', '{Carnatic music,Cooking}',
  '{Running,Fitness,Yoga}', '{Tech,Board games,Movies}', '{Coffee,Art,Travel}',
  '{Pets,Cooking,Reading}', '{Board games,Movies,Foodie}', '{Photography,Cycling}',
  '{Cooking,Music,Dance}', '{Cycling,Fitness,Coffee}', '{Reading,Coffee,Travel}'
]::text[][])[1 + (abs(hashtext(id::text)) % 12)]
where id in (select id from auth.users where raw_app_meta_data->>'seed' = 'true');

-- Max 6 photos per person
create or replace function public.enforce_photo_limit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from photos where user_id = new.user_id) >= 6 then
    raise exception 'You can have up to 6 photos';
  end if;
  return new;
end $$;

drop trigger if exists photos_limit on public.photos;
create trigger photos_limit before insert on public.photos
  for each row execute function public.enforce_photo_limit();

-- Always keep at least one photo (except when the whole account is being deleted)
create or replace function public.keep_one_photo() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from profiles where id = old.user_id) then
    return old; -- account deletion in progress
  end if;
  if (select count(*) from photos where user_id = old.user_id) <= 1 then
    raise exception 'Keep at least one photo on your profile';
  end if;
  return old;
end $$;

drop trigger if exists photos_keep_one on public.photos;
create trigger photos_keep_one before delete on public.photos
  for each row execute function public.keep_one_photo();

-- Make a photo the main one: it moves to the front, the rest keep their order
create or replace function public.set_main_photo(p_photo_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from photos where id = p_photo_id and user_id = auth.uid()) then
    raise exception 'Not allowed';
  end if;
  with ordered as (
    select id,
           row_number() over (order by (id = p_photo_id) desc, position, created_at) - 1 as pos
    from photos where user_id = auth.uid()
  )
  update photos p set position = o.pos from ordered o where p.id = o.id;
end $$;

grant execute on function public.set_main_photo(uuid) to authenticated;
