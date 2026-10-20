create table establishments (id uuid primary key, name varchar(160) not null, address varchar(300) not null);
create table app_users (
 id uuid primary key, email varchar(254) not null unique, name varchar(100) not null,
 phone varchar(25) not null, password_hash varchar(100) not null,
 role varchar(20) not null check(role in ('USER','SELLER','ADMIN')),
 establishment_id uuid references establishments(id), blocked boolean not null default false,
 two_factor boolean not null default false, must_change_password boolean not null default false,
 failed_logins integer not null default 0, locked_until timestamp with time zone,
 session_version integer not null default 0,
 check (role <> 'SELLER' or establishment_id is not null)
);
create table auth_challenges (
 id uuid primary key, email varchar(254) not null, purpose varchar(30) not null,
 code_hash varchar(100) not null, expires_at timestamp with time zone not null,
 sent_at timestamp with time zone not null, attempts integer not null default 0,
 consumed boolean not null default false, name varchar(100), phone varchar(25),
 password_hash varchar(100), session_version integer not null default 0
);
create index challenge_email_purpose on auth_challenges(email, purpose, sent_at);
create table admin_audit (id uuid primary key, actor_id uuid not null, action varchar(100) not null, target_id uuid not null, occurred_at timestamp with time zone not null);
