-- The merchant replica is now fed by merchant.snapshots.v1. Rows seeded through the old temporary OPS
-- endpoint carry versions from that endpoint's own counter; reset them so the first real snapshot of each
-- merchant (version 1 and up) replaces them.
UPDATE merchant_config_replica SET snapshot_version = 0;
