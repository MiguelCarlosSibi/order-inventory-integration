-- Run this in the Supabase SQL editor (Project -> SQL Editor -> New query)
-- before starting the backend. spring.jpa.hibernate.ddl-auto=validate means
-- the app expects these tables to already exist and match.

create table if not exists inventory (
    product_id varchar(20) primary key,
    name       varchar(100) not null,
    stock      integer not null check (stock >= 0)
);

create table if not exists orders (
    order_id   bigserial primary key,
    product_id varchar(20) not null references inventory (product_id),
    quantity   integer not null check (quantity > 0),
    status     varchar(20) not null check (status in ('CONFIRMED', 'REJECTED')),
    reason     varchar(255),
    created_at timestamptz not null default now()
);

-- Seed data as specified in the assignment.
insert into inventory (product_id, name, stock) values
    ('P100', 'Wireless Mouse', 25),
    ('P200', 'Mechanical Keyboard', 10),
    ('P300', 'USB-C Hub', 0)
on conflict (product_id) do nothing;
