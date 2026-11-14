package tutor.identityservice.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tutor.identityservice.dto.RegistrateDTO;
import tutor.identityservice.entity.AppUser;
import tutor.identityservice.entity.Role;
import tutor.identityservice.entity.UserRole;
import tutor.identityservice.exception.InvitationCodeExcpetion;
import tutor.identityservice.exception.RegistrationLoginTakenException;
import tutor.identityservice.repository.AppUserRepository;
import tutor.identityservice.repository.UserRoleRepository;

@Service
@Validated
public class CreateUserService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleRepository userRoleRepository;
    private final InvitationCodeService invitationCodeService;
    private final TeacherRegistrationCodeService teacherRegistrationCodeService;

    public CreateUserService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, UserRoleRepository userRoleRepository, InvitationCodeService invitationCodeService, TeacherRegistrationCodeService teacherRegistrationCodeService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.userRoleRepository = userRoleRepository;
        this.invitationCodeService = invitationCodeService;
        this.teacherRegistrationCodeService = teacherRegistrationCodeService;
    }

    @Transactional()
    public void registerParent(@NotNull @Valid RegistrateDTO registrateDTO) {
        if(!invitationCodeService.consumeInvitationCode(registrateDTO.invitationCode())) {
            throw new InvitationCodeExcpetion();
        }

        saveUser(registrateDTO.login(),
                registrateDTO.password(),
                Role.PARENT,
                false);
    }

    @Transactional()
    public void registerTeacher(@NotNull @Valid RegistrateDTO registrateDTO) {
        if(!this.teacherRegistrationCodeService.consumeInvitationCode(registrateDTO.invitationCode())) {
            throw new InvitationCodeExcpetion();
        }


        saveUser(registrateDTO.login(),
                registrateDTO.password(),
                Role.TEACHER,
                false);
    }

    protected void saveUser(String login, String password, Role role, boolean nameRequested) {
        try {
            AppUser appUser = new AppUser();

            appUser.setLogin(login);
            appUser.setBlocked(false);
            appUser.setPasswordHash(passwordEncoder.encode(password));
            appUser.setNameRequested(nameRequested);

            var userRole = new UserRole(appUser, role);

            appUserRepository.saveAndFlush(appUser);
            userRoleRepository.saveAndFlush(userRole);
        } catch (Exception ex) {
            if(isLoginTaken(ex)) {
                throw new RegistrationLoginTakenException(ex);
            }

            throw ex;
        }
    }

    private boolean isLoginTaken(Throwable exception) {
        for (Throwable cause = exception;
             cause != null;
             cause = cause.getCause()) {

            if (cause instanceof ConstraintViolationException violation
                    && "uc_app_user_login".equals(violation.getConstraintName())) {
                return true;
            }
        }

        return false;
    }
}
