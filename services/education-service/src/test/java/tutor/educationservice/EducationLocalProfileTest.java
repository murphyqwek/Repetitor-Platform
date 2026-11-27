package tutor.educationservice;

import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "local", inheritProfiles = false)
class EducationLocalProfileTest extends EducationContextTest {}
