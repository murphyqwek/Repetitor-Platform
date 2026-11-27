-- Existing invitations have no known owner and cannot be revoked by a teacher.
ALTER TABLE invitation_code
    ADD COLUMN teacher_id UUID REFERENCES app_user (id);
