-- Seed for the demo's second source, on a different engine from the first. The official image runs
-- every .sql here once, on an empty data directory only, so this reflects a first boot and not a
-- re-run over the named volume.
--
-- These are the shipments belonging to the orders seeded into MySQL next door. The two halves are
-- deliberately kept in separate engines: an order and its shipments are one business object, and
-- nothing in either database can join them, because neither database can see the other.
--
-- order_id is the tie back to the MySQL side. It carries no foreign key and cannot: the table it
-- would reference lives in another engine. That is a property of the demo, not an omission - the
-- product is what reconciles the two, and a constraint here would only be able to express a
-- relationship that is already unenforceable.
CREATE TABLE IF NOT EXISTS shipments (
  id       INT PRIMARY KEY,
  order_id INT NOT NULL,
  carrier  VARCHAR(64),
  status   VARCHAR(32)
);

-- Two orders with two shipments each, two with one, and one with none. The uneven spread is the
-- point: an assembly that quietly drops the second shipment, or that invents an empty array for the
-- order that has none, is wrong in a way a uniform one-each seeding would hide.
INSERT INTO shipments (id, order_id, carrier, status) VALUES
  (1, 1, 'dhl',   'delivered'),
  (2, 1, 'ups',   'in_transit'),
  (3, 2, 'fedex', 'delivered'),
  (4, 2, 'dhl',   'pending'),
  (5, 3, 'ups',   'delivered'),
  (6, 4, 'fedex', 'in_transit');

-- Replication needs a way to identify the row a change belongs to. A primary key already provides
-- it, so this is not a fix for these tables - it is here because the demo advertises the replication
-- posture it documents, and because a user who copies this file for a table without a primary key
-- hits the failure at seed time rather than mid-stream.
ALTER TABLE shipments REPLICA IDENTITY DEFAULT;
