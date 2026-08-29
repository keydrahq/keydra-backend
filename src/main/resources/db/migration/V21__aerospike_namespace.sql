-- Which Aerospike namespace a profile points at.
--
-- Beside `sentinel_master_name` and for the same reason: a field one arrangement needs and the
-- others have no use for. It is what `database` is to a RESP target, except that an Aerospike
-- namespace is named rather than numbered, so it could not reuse that column.
--
-- Null for every profile written before this, which is every RESP one — and for a RESP one it
-- stays null, because nothing reads it there.
ALTER TABLE connection_profile ADD COLUMN namespace VARCHAR(255);
