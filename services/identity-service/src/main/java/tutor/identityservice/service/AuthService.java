package tutor.identityservice.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import tutor.identityservice.dto.LoginDTO;
import tutor.identityservice.dto.TokensDTO;
import tutor.identityservice.exception.RefreshTokenException;
import tutor.identityservice.exception.UserIsBlockedException;
import tutor.identityservice.security.IdentityUserDetails;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService, RefreshTokenService refreshTokenService, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userService = userService;
    }

    public TokensDTO login(LoginDTO credentials) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        credentials.login(), credentials.password()));

        var user = (IdentityUserDetails) authentication.getPrincipal();

        String accessToken = jwtService.generateToken(user.getUserId(), user.getRoles());
        String refreshToken = refreshTokenService.createRefreshToken(user.getUserId());

        TokensDTO tokens = new TokensDTO(accessToken, refreshToken);

        return tokens;
    }

    public TokensDTO refreshToken(String refreshToken) {
        var uuid = refreshTokenService.checkTokenAndRefresh(refreshToken).orElseThrow(() -> new RefreshTokenException());
        var roles = userService.getUserRoles(uuid);
        var isBlocked = userService.isUserBlocked(uuid);

        if(isBlocked) {
            throw new UserIsBlockedException();
        }

        String accessToken = jwtService.generateToken(uuid, roles);
        String newRefreshToken = refreshTokenService.createRefreshToken(uuid);

        TokensDTO tokens = new TokensDTO(accessToken, newRefreshToken);

        return tokens;
    }

    public void logout(String refreshToken) {
        refreshTokenService.deleteRefreshToken(refreshToken);
    }
}
