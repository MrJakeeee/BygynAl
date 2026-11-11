-- Create the requested BugunAL schema after the old local application tables
-- have been removed manually. Flyway migration history is intentionally kept.

CREATE TABLE images (
    id uuid PRIMARY KEY,
    content_type varchar(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    data bytea NOT NULL CHECK (octet_length(data) <= 5 * 1024 * 1024),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE establishments (
    id uuid PRIMARY KEY,
    name varchar(160) NOT NULL,
    address varchar(300) NOT NULL,
    place_type varchar(20),
    phone varchar(25),
    opening_hours varchar(100),
    description varchar(1000),
    logo_id uuid REFERENCES images(id)
);

CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    name varchar(100) NOT NULL,
    phone varchar(25) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'SELLER', 'ADMIN')),
    establishment_id uuid REFERENCES establishments(id),
    blocked boolean NOT NULL DEFAULT false,
    two_factor boolean NOT NULL DEFAULT false,
    must_change_password boolean NOT NULL DEFAULT false,
    failed_logins integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    session_version integer NOT NULL DEFAULT 0,
    CHECK (role <> 'SELLER' OR establishment_id IS NOT NULL)
);

CREATE TABLE auth_challenges (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    purpose varchar(30) NOT NULL,
    code_hash varchar(100) NOT NULL,
    expires_at timestamptz NOT NULL,
    sent_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    consumed boolean NOT NULL DEFAULT false,
    name varchar(100),
    phone varchar(25),
    password_hash varchar(100),
    session_version integer NOT NULL DEFAULT 0
);
CREATE INDEX challenge_email_purpose ON auth_challenges(email, purpose, sent_at);

CREATE TABLE admin_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    action varchar(100) NOT NULL,
    target_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE offers (
    id uuid PRIMARY KEY,
    establishment_id uuid NOT NULL REFERENCES establishments(id),
    title varchar(120) NOT NULL,
    composition varchar(1000) NOT NULL,
    allergens varchar(300),
    original_price numeric(10,2) NOT NULL,
    price numeric(10,2) NOT NULL,
    total_portions integer NOT NULL,
    remaining integer NOT NULL,
    pickup_date date NOT NULL,
    pickup_start time NOT NULL,
    pickup_end time NOT NULL,
    withdrawn boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (price > 0 AND price < original_price),
    CHECK (total_portions BETWEEN 1 AND 100),
    CHECK (remaining >= 0 AND remaining <= total_portions),
    CHECK (pickup_end > pickup_start)
);
CREATE INDEX offers_establishment ON offers(establishment_id, pickup_date);

CREATE TABLE offer_photos (
    offer_id uuid NOT NULL REFERENCES offers(id) ON DELETE CASCADE,
    sort_order integer NOT NULL CHECK (sort_order BETWEEN 1 AND 3),
    image_id uuid NOT NULL REFERENCES images(id),
    PRIMARY KEY (offer_id, sort_order)
);

CREATE TABLE orders (
    id uuid PRIMARY KEY,
    offer_id uuid NOT NULL REFERENCES offers(id),
    buyer_id uuid NOT NULL REFERENCES app_users(id),
    quantity integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
    status varchar(30) NOT NULL DEFAULT 'AWAITING_PAYMENT'
        CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'ISSUED', 'CANCELLED', 'EXPIRED')),
    amount numeric(10,2) NOT NULL CHECK (amount > 0),
    pickup_code char(6) CHECK (pickup_code ~ '^[0-9]{6}$'),
    reserved_until timestamptz NOT NULL,
    paid_at timestamptz,
    issued_at timestamptz,
    issued_by uuid REFERENCES app_users(id),
    refund_status varchar(30) NOT NULL DEFAULT 'NONE'
        CHECK (refund_status IN ('NONE', 'PENDING', 'REFUNDED', 'FAILED')),
    CHECK (status <> 'ISSUED' OR (issued_at IS NOT NULL AND issued_by IS NOT NULL))
);
CREATE INDEX orders_status_reserved ON orders(status, reserved_until);
CREATE INDEX orders_offer ON orders(offer_id);
CREATE INDEX orders_buyer ON orders(buyer_id);

CREATE TABLE payments (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    provider_payment_id varchar(100) NOT NULL UNIQUE,
    kind varchar(20) NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND')),
    amount numeric(10,2) NOT NULL CHECK (amount > 0),
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payments_order ON payments(order_id);
