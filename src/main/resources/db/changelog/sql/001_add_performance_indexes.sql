-- Performance indexes for OPAC (orque_opac database)
-- OPAC runs with spring.jpa.hibernate.ddl-auto=none, so these indexes are NOT
-- created automatically — run this script once against the production database.
--
-- Usage: psql -U <user> -d orque_opac -f 001_add_performance_indexes.sql

CREATE INDEX IF NOT EXISTS idx_license_request_status       ON license_request (status);
CREATE INDEX IF NOT EXISTS idx_license_request_tenant_uuid  ON license_request (tenant_uuid);
CREATE INDEX IF NOT EXISTS idx_license_request_requested_by ON license_request (requested_by);

CREATE INDEX IF NOT EXISTS idx_user_master_tenant_uuid ON user_master (tenant_uuid);
CREATE INDEX IF NOT EXISTS idx_user_master_status      ON user_master (status);

CREATE INDEX IF NOT EXISTS idx_approval_request_reference_uuid ON approval_request (reference_uuid);

CREATE INDEX IF NOT EXISTS idx_tenant_request_status ON tenant_request (status);

CREATE INDEX IF NOT EXISTS idx_notification_master_tenant_uuid ON notification_master (tenant_uuid);

CREATE INDEX IF NOT EXISTS idx_email_queue_tenant_uuid ON email_queue (tenant_uuid);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_uuid ON audit_log (tenant_uuid);
