-- Run this in the Supabase SQL editor (Project -> SQL Editor -> New query).
-- FULL rebuild, now through Lab 3: adds supplier_orders for the LegacySupply
-- Anti-Corruption Layer (Part C) on top of everything from Lab 2. Running
-- this drops and recreates every table from scratch, including re-seeding
-- inventory — all existing orders/notifications/supplier_orders data will
-- be lost. Don't hand-edit Supabase directly; this script is the single
-- source of truth for the schema.

drop table if exists channel_events;
drop table if exists channel_orders;
drop table if exists channel_cursor;
drop table if exists supplier_orders;
drop table if exists order_items;
drop table if exists notifications;
drop table if exists orders;
drop table if exists inventory;

create table inventory (
    product_id varchar(20) primary key,
    name       varchar(100) not null,
    stock      integer not null check (stock >= 0)
);

create table orders (
    order_id   bigserial primary key,
    status     varchar(20) not null check (status in ('CONFIRMED', 'REJECTED', 'CANCELLED', 'BACKORDERED')),
    reason     varchar(255),
    created_at timestamptz not null default now()
);

create table order_items (
    order_item_id bigserial primary key,
    order_id      bigint not null references orders (order_id) on delete cascade,
    product_id    varchar(20) not null references inventory (product_id),
    quantity      integer not null check (quantity > 0),
    outcome       varchar(255) not null default 'OK'
);

create table notifications (
    notification_id bigserial primary key,
    message          varchar(500) not null,
    created_at       timestamptz not null default now()
);

create table supplier_orders (
    id          bigserial primary key,
    product_id  varchar(20) not null references inventory (product_id),
    buyer_ref   varchar(40) unique,
    request_id  varchar(80) not null unique,
    po_number   varchar(50),
    cases       integer not null check (cases > 0),
    units       integer not null check (units > 0),
    status      varchar(20) not null check (status in ('PENDING', 'ACCEPTED', 'PICKING', 'SHIPPED', 'DELIVERED', 'FAILED')),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

-- Lab 4 (Tiangge channel). Column names match ChannelStore.
create table channel_cursor (
    id       integer primary key check (id = 1),
    last_seq bigint not null default 0
);
insert into channel_cursor (id, last_seq) values (1, 0);

create table channel_orders (
    tiangge_order_id varchar(100) primary key,
    shop_order_id    bigint not null references orders (order_id),
    decision         varchar(20) not null check (decision in ('ACCEPTED', 'BACKORDERED', 'REJECTED')),
    placed_at        timestamptz not null,
    decision_sent    boolean not null default false,
    resolved         boolean not null default false,
    cancel_confirmed boolean not null default false
);

create table channel_events (
    event_id         varchar(100) primary key,
    seq              bigint not null,
    type             varchar(40) not null,
    tiangge_order_id varchar(100) not null,
    seen_at          timestamptz not null default now()
);

-- Seed data as specified in the assignment.
insert into inventory (product_id, name, stock) values
    ('P100', 'Wireless Mouse', 25),
    ('P200', 'Mechanical Keyboard', 10),
    ('P300', 'USB-C Hub', 0)
on conflict (product_id) do nothing;
