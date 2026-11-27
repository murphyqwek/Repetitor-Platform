ALTER TABLE invitation_code
    ADD CONSTRAINT uc_invitation_code_hash UNIQUE (code_hash);

ALTER TABLE teacher_registration_code
    ADD CONSTRAINT uc_teacher_registration_code_hash UNIQUE (code_hash);
