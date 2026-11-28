package tutor.identityservice;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;

final class AssignmentCredential {
    private static final byte[] key = new byte[32];
    static { new java.security.SecureRandom().nextBytes(key); }
    static String encoded() { return Base64.getEncoder().encodeToString(key); }
    static String valid() throws Exception { return token("identity-service", "users:read", "SERVICE"); }
    static String token(String audience, String scope, String type) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("rp-assignment").subject("assignment-service").audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", List.of(scope)).claim("tokenType", type).build());
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }
}
