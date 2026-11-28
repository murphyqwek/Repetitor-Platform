CREATE TABLE subject (
    id smallint PRIMARY KEY,
    name text NOT NULL UNIQUE CHECK (btrim(name) <> '')
);
CREATE TABLE topic (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id smallint NOT NULL REFERENCES subject(id),
    name text NOT NULL CHECK (btrim(name) <> ''),
    is_active boolean NOT NULL DEFAULT true,
    UNIQUE(subject_id, name)
);
CREATE INDEX topic_name_page ON topic(name, id);
CREATE TABLE exam_type (
    id smallint PRIMARY KEY,
    subject_id smallint NOT NULL REFERENCES subject(id),
    code text NOT NULL UNIQUE,
    name text NOT NULL CHECK (btrim(name) <> '')
);
CREATE TABLE exam_definition (
    id uuid PRIMARY KEY,
    exam_type_id smallint NOT NULL REFERENCES exam_type(id),
    year smallint NOT NULL CHECK (year > 0),
    current_structure_version_id uuid,
    created_at timestamptz NOT NULL,
    UNIQUE(exam_type_id, year)
);
CREATE INDEX exam_year_page ON exam_definition(year DESC, id DESC);
CREATE TABLE exam_structure_version (
    id uuid PRIMARY KEY,
    exam_definition_id uuid NOT NULL REFERENCES exam_definition(id),
    version integer NOT NULL CHECK (version > 0),
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 1440),
    created_at timestamptz NOT NULL,
    UNIQUE(exam_definition_id, version),
    UNIQUE(exam_definition_id, id)
);
ALTER TABLE exam_definition ADD CONSTRAINT exam_current_structure_fk
    FOREIGN KEY(id, current_structure_version_id) REFERENCES exam_structure_version(exam_definition_id, id);
CREATE TABLE exam_position (
    id uuid PRIMARY KEY,
    structure_version_id uuid NOT NULL REFERENCES exam_structure_version(id),
    position smallint NOT NULL CHECK (position BETWEEN 1 AND 100),
    max_score integer NOT NULL CHECK (max_score BETWEEN 1 AND 100),
    check_mode text NOT NULL CHECK (check_mode IN ('AUTOMATIC','MANUAL')),
    UNIQUE(structure_version_id, position)
);
CREATE TABLE exam_position_topic (
    exam_position_id uuid NOT NULL REFERENCES exam_position(id),
    topic_id integer NOT NULL REFERENCES topic(id),
    PRIMARY KEY(exam_position_id, topic_id)
);
CREATE INDEX exam_position_topic_reverse ON exam_position_topic(topic_id, exam_position_id);
CREATE TABLE grading_scale (
    id uuid PRIMARY KEY,
    exam_definition_id uuid NOT NULL REFERENCES exam_definition(id),
    name text NOT NULL CHECK (btrim(name) <> ''),
    current_version_id uuid
);
CREATE INDEX grading_scale_exam ON grading_scale(exam_definition_id, name, id);
CREATE TABLE grading_scale_version (
    id uuid PRIMARY KEY,
    scale_id uuid NOT NULL REFERENCES grading_scale(id),
    structure_version_id uuid NOT NULL REFERENCES exam_structure_version(id),
    version integer NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL,
    UNIQUE(scale_id, version),
    UNIQUE(scale_id, id)
);
CREATE INDEX grading_scale_structure ON grading_scale_version(structure_version_id);
ALTER TABLE grading_scale ADD CONSTRAINT scale_current_version_fk
    FOREIGN KEY(id, current_version_id) REFERENCES grading_scale_version(scale_id, id);
CREATE TABLE scale_value (
    scale_version_id uuid NOT NULL REFERENCES grading_scale_version(id),
    primary_score integer NOT NULL CHECK (primary_score >= 0),
    test_score integer CHECK (test_score BETWEEN 0 AND 100),
    school_mark smallint CHECK (school_mark BETWEEN 2 AND 5),
    PRIMARY KEY(scale_version_id, primary_score),
    CHECK ((test_score IS NULL) <> (school_mark IS NULL))
);

-- Cross-table ownership is also enforced for direct SQL writes.
CREATE FUNCTION check_scale_exam() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT exam_definition_id FROM grading_scale WHERE id=NEW.scale_id)
        IS DISTINCT FROM
       (SELECT exam_definition_id FROM exam_structure_version WHERE id=NEW.structure_version_id) THEN
        RAISE EXCEPTION 'Scale and structure must belong to the same exam' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER scale_exam_guard BEFORE INSERT OR UPDATE ON grading_scale_version
    FOR EACH ROW EXECUTE FUNCTION check_scale_exam();

CREATE FUNCTION check_position_topic_subject() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT et.subject_id FROM exam_position p
         JOIN exam_structure_version v ON v.id=p.structure_version_id
         JOIN exam_definition e ON e.id=v.exam_definition_id
         JOIN exam_type et ON et.id=e.exam_type_id WHERE p.id=NEW.exam_position_id)
       IS DISTINCT FROM (SELECT subject_id FROM topic WHERE id=NEW.topic_id) THEN
        RAISE EXCEPTION 'Position and topic subjects differ' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER position_topic_subject_guard BEFORE INSERT OR UPDATE ON exam_position_topic
    FOR EACH ROW EXECUTE FUNCTION check_position_topic_subject();

INSERT INTO subject(id,name) VALUES (1,'Математика');
INSERT INTO exam_type(id,subject_id,code,name) VALUES
    (1,1,'EGE_PROFILE','ЕГЭ профильная математика'),
    (2,1,'EGE_BASE','ЕГЭ базовая математика'),
    (3,1,'OGE','ОГЭ математика');
