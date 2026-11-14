-- Patient SMS (Phase 9c): a third channel next to WEBHOOK and EMAIL. The phone number is encrypted at rest
-- in recipient_enc like email addresses.
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_channel;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_channel CHECK (channel IN ('WEBHOOK', 'EMAIL', 'SMS'));
