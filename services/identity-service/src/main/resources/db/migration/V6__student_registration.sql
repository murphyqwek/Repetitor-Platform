ALTER TABLE student_reg_request
    ADD COLUMN school_grade SMALLINT CHECK (school_grade BETWEEN 0 AND 11),
    ADD COLUMN policy_version VARCHAR(100),
    ADD COLUMN consent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD CONSTRAINT uc_student_request_key UNIQUE (parent_id, idempotency_key),
    ADD CONSTRAINT ck_student_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    ADD CONSTRAINT ck_student_request_result CHECK ((status = 'APPROVED') = (student_id IS NOT NULL));

ALTER TABLE student_profile ADD CONSTRAINT ck_student_grade CHECK (school_grade BETWEEN 0 AND 11);
ALTER TABLE app_user ADD COLUMN accepted_policy_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE student_profile
    ADD COLUMN guardian_id UUID REFERENCES app_user(id),
    ADD COLUMN consent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN policy_version VARCHAR(100);
CREATE INDEX ix_student_request_parent ON student_reg_request (parent_id, id DESC);
CREATE INDEX ix_student_request_pending ON student_reg_request (id DESC) WHERE status = 'PENDING';
CREATE INDEX ix_parent_student_student ON parent_student (student_id);
CREATE INDEX ix_invitation_teacher_active ON invitation_code (teacher_id, expired_at);
