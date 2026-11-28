CREATE TABLE stored_file (
    id uuid PRIMARY KEY,
    uploader_id uuid,
    object_key text NOT NULL UNIQUE CHECK (object_key ~ '^files/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'),
    original_name text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 255),
    mime_type text NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    content_hash text CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    purpose text NOT NULL CHECK (purpose IN ('TASK_IMAGE','ANSWER_ATTACHMENT')),
    state text NOT NULL CHECK (state IN ('UPLOADING','READY','ATTACHED','DELETING')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    updated_at timestamptz NOT NULL,
    upload_locked_until timestamptz,
    upload_lease_token uuid,
    CHECK ((upload_locked_until IS NULL) = (upload_lease_token IS NULL)),
    CHECK (state <> 'UPLOADING' OR upload_lease_token IS NOT NULL),
    CHECK (state NOT IN ('UPLOADING','READY') OR expires_at IS NOT NULL),
    CHECK (state <> 'ATTACHED' OR expires_at IS NULL),
    CHECK (state NOT IN ('READY','ATTACHED') OR content_hash IS NOT NULL)
);
CREATE INDEX stored_file_expiry ON stored_file(expires_at,id) WHERE state IN ('UPLOADING','READY');
CREATE INDEX stored_file_upload_lease ON stored_file(upload_locked_until,id) WHERE state='UPLOADING';

CREATE TABLE cleanup_job (
    id uuid PRIMARY KEY,
    resource_type text NOT NULL CHECK (resource_type IN ('STORED_FILE','TASK','MATERIAL_REFERENCE')),
    resource_id uuid NOT NULL,
    -- Retained after metadata deletion to fence and reconcile late writes.
    object_key text,
    status text NOT NULL CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    locked_until timestamptz,
    lease_token uuid,
    last_error text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((locked_until IS NULL) = (lease_token IS NULL)),
    CHECK (resource_type <> 'STORED_FILE' OR object_key IS NOT NULL)
);
CREATE UNIQUE INDEX cleanup_job_active_resource ON cleanup_job(resource_type,resource_id)
    WHERE status IN ('PENDING','PROCESSING','FAILED');
CREATE INDEX cleanup_job_due ON cleanup_job(status,next_attempt_at,id);
CREATE INDEX cleanup_job_lease ON cleanup_job(locked_until) WHERE status='PROCESSING';
CREATE INDEX cleanup_job_object_key ON cleanup_job(object_key);
