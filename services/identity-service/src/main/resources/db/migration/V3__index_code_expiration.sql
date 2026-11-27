CREATE INDEX idx_invitation_code_expired_at
    ON invitation_code (expired_at);

CREATE INDEX idx_teacher_registration_code_expired_at
    ON teacher_registration_code (expired_at);
