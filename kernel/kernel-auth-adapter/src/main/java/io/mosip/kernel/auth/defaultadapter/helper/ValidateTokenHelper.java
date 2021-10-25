package io.mosip.kernel.auth.defaultadapter.helper;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.mosip.kernel.auth.defaultadapter.constant.AuthAdapterConstant;
import io.mosip.kernel.core.authmanager.authadapter.model.MosipUserDto;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.exception.ServiceError;
import io.mosip.kernel.core.util.DateUtils;

@Component
public class ValidateTokenHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValidateTokenHelper.class);

    private Map<String, PublicKey> publicKeys = new HashMap<>();

    @Value("${auth.server.admin.oidc.certs.path:/protocol/openid-connect/certs}")
    private String certsPath;

    @Value("${auth.server.admin.oidc.userinfo.path:/protocol/openid-connect/userinfo}")
    private String userInfo;

    @Value("${auth.server.admin.issuer.domain.validate:true}")
    private boolean validateIssuerDomain;

    @Value("${auth.server.admin.issuer.uri:}")
    private String issuerURI;

    @Value("${auth.server.admin.audience.claim.validate:true}")
    private boolean validateAudClaim;

    //@Value("${auth.server.admin.allowed.audience:}")
    private List<String> allowedAudience;

    @Autowired
	private ObjectMapper objectMapper;

    @Autowired
	private Environment environment;

    @PostConstruct
    @SuppressWarnings("unchecked")
	private void init(){
        String applName = getApplicationName();
        this.allowedAudience = (List<String>) environment.getProperty("auth.server.admin.allowed.audience." + applName, List.class,
                    environment.getProperty("auth.server.admin.allowed.audience", List.class, null));
    }

    private String getApplicationName() {
		String appNames = environment.getProperty("spring.application.name");
		List<String> appNamesList = Stream.of(appNames.split(",")).collect(Collectors.toList());
		return appNamesList.get(0);
	}

    public MosipUserDto doOfflineLocalTokenValidation(String jwtToken) {
        LOGGER.info("offline verification for local profile.");
        DecodedJWT decodedJWT = JWT.require(Algorithm.none()).build().verify(jwtToken);
		return buildMosipUser(decodedJWT, jwtToken);
    }

    public boolean isTokenValid(DecodedJWT decodedJWT, PublicKey publicKey) {
        // First, token expire    
        LocalDateTime expiryTime = DateUtils.convertUTCToLocalDateTime(DateUtils.getUTCTimeFromDate(decodedJWT.getExpiresAt()));
        if (!DateUtils.before(DateUtils.getUTCCurrentDateTime(), expiryTime)) {
            LOGGER.error("Provided Auth Token expired. Throwing Authorizaion Exception");
            return false;
        }

        // Second, issuer domain check.
        boolean tokenDomainMatch = getTokenIssuerDomain(decodedJWT);
        if (validateIssuerDomain && !tokenDomainMatch){
            LOGGER.error("Provided Auth Token Issue domain does not match. Throwing Authorizaion Exception");
            return false;
        }

        // Third, signature validation.
        try {
            String tokenAlgo = decodedJWT.getAlgorithm();
            Algorithm algorithm = getVerificationAlgorithm(tokenAlgo, publicKey);
            algorithm.verify(decodedJWT);
        } catch(SignatureVerificationException signatureException) {
            LOGGER.error("Signature validation failed, Throwing Authorization Exception.", signatureException);
            return false;
        }

        // Fourth, audience | azp validation.
        boolean matchFound = validateAudience(decodedJWT);
        // No match found after comparing audience & azp
        if (!matchFound) {
            LOGGER.error("Provided Client Id does not match with Aud/AZP. Throwing Authorizaion Exception");
            return false;
        }
        return true;
    }

    private boolean validateAudience(DecodedJWT decodedJWT){
        boolean matchFound = false;
        if (validateAudClaim){
            
            List<String> tokenAudience = decodedJWT.getAudience();
            matchFound = tokenAudience.stream().anyMatch(allowedAudience::contains);

            // comparing with azp.
            String azp = decodedJWT.getClaim(AuthAdapterConstant.AZP).asString();
            if (!matchFound) {
                matchFound = allowedAudience.stream().anyMatch(azp::equalsIgnoreCase);
            }
        }
        return matchFound;
    }

    private boolean getTokenIssuerDomain(DecodedJWT decodedJWT) {
        String domain = decodedJWT.getClaim(AuthAdapterConstant.ISSUER).asString();
        try {
            String tokenHost = new URI(domain).getHost();
            String issuerHost = new URI(issuerURI).getHost();
            return tokenHost.equalsIgnoreCase(issuerHost);
        } catch (URISyntaxException synExp) {
            LOGGER.error("Unable to parse domain from issuer.", synExp);
        }
        return false;
    }

    public PublicKey getPublicKey(DecodedJWT decodedJWT) {
        LOGGER.info("offline verification for environment profile.");
        
        String keyId = decodedJWT.getKeyId();
        PublicKey publicKey = publicKeys.get(keyId);

        if (Objects.isNull(publicKey)) {
            String realm = getRealM(decodedJWT);
            publicKey = getIssuerPublicKey(keyId, certsPath, realm);
            publicKeys.put(keyId, publicKey);
        }
        return publicKey;
    }

    private String getRealM(DecodedJWT decodedJWT) {
        String tokenIssuer = decodedJWT.getClaim(AuthAdapterConstant.ISSUER).asString();
        return tokenIssuer.substring(tokenIssuer.lastIndexOf("/") + 1);
    }

    private PublicKey getIssuerPublicKey(String keyId, String certsPath, String realm) {
        try {
            
            URI uri = new URI(issuerURI + realm + certsPath).normalize();
            JwkProvider provider = new UrlJwkProvider(uri.toURL());
            Jwk jwk = provider.get(keyId);
            return jwk.getPublicKey();
        } catch (JwkException | URISyntaxException | MalformedURLException e) {
            LOGGER.error("Error downloading Public key from server".concat(e.getMessage()));
        }
        return null;        
    }

    private Algorithm getVerificationAlgorithm(String tokenAlgo, PublicKey publicKey){
        // Later will add other Algorithms.
        switch (tokenAlgo) {
            case "RS256":
                return Algorithm.RSA256((RSAPublicKey) publicKey, null);
            case "RS384":
                return Algorithm.RSA384((RSAPublicKey) publicKey, null);
            case "RS512":
                return Algorithm.RSA512((RSAPublicKey) publicKey, null);
            default:
                return Algorithm.RSA256((RSAPublicKey) publicKey, null);
        }
    }

    @SuppressWarnings("unchecked")
    public MosipUserDto buildMosipUser(DecodedJWT decodedJWT, String jwtToken) {
        MosipUserDto mosipUserDto = new MosipUserDto();
        String user = decodedJWT.getSubject();
		mosipUserDto.setToken(jwtToken);
		mosipUserDto.setMail(decodedJWT.getClaim(AuthAdapterConstant.EMAIL).asString());
		mosipUserDto.setMobile(decodedJWT.getClaim(AuthAdapterConstant.MOBILE).asString());
        mosipUserDto.setUserId(decodedJWT.getClaim(AuthAdapterConstant.PREFERRED_USERNAME).asString());
        Claim realmAccess = decodedJWT.getClaim(AuthAdapterConstant.REALM_ACCESS);
        if (!realmAccess.isNull()) {
            List<String> roles = (List<String>) realmAccess.asMap().get("roles");
            StringBuilder strBuilder = new StringBuilder();

            for (String role : roles) {
                strBuilder.append(role);
                strBuilder.append(AuthAdapterConstant.COMMA);
            }
            mosipUserDto.setRole(strBuilder.toString());
            mosipUserDto.setName(user);
        } else {
            mosipUserDto.setRole(decodedJWT.getClaim(AuthAdapterConstant.ROLES).asString());
            mosipUserDto.setName(user);
        }
		
        LOGGER.info("user (offline verificate): " + mosipUserDto.getUserId());
		return mosipUserDto;
    }

    public ImmutablePair<HttpStatus, MosipUserDto> doOnlineTokenValidation(String jwtToken, RestTemplate restTemplate) {
        if ("".equals(issuerURI)) {
            LOGGER.warn("OIDC validate URL is not available in config file, not requesting for token validation.");
            return ImmutablePair.of(HttpStatus.EXPECTATION_FAILED, null);
        }

        DecodedJWT decodedJWT = JWT.decode(jwtToken);
		HttpHeaders headers = new HttpHeaders();
		headers.add(AuthAdapterConstant.AUTH_REQUEST_COOOKIE_HEADER, AuthAdapterConstant.BEARER_STR + jwtToken);
		HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
        ResponseEntity<String> response = null;
        HttpStatusCodeException statusCodeException = null;
		try {
            String realm = getRealM(decodedJWT);
            String userInfoPath = issuerURI + realm + userInfo;
			response = restTemplate.exchange(userInfoPath, HttpMethod.GET, entity, String.class);
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			LOGGER.error("Token validation failed for accessToken {}", jwtToken, e);
            statusCodeException = e;
		}
        
        if (Objects.nonNull(statusCodeException)){
            JsonNode errorNode;
            try {
                errorNode = objectMapper.readTree(statusCodeException.getResponseBodyAsString());
                LOGGER.error("Token validation failed error {} and message {}", errorNode.get(AuthAdapterConstant.ERROR), 
                                errorNode.get(AuthAdapterConstant.ERROR_DESC));
                return ImmutablePair.of(statusCodeException.getStatusCode(), null);
            } catch (IOException e) {
                LOGGER.error("IO Excepton in parsing response {}", e.getMessage());
            }
        }

        if (response.getStatusCode().is2xxSuccessful()) {
            // validating audience | azp claims.
            boolean matchFound = validateAudience(decodedJWT);
            if (!matchFound) {
                LOGGER.error("Provided Client Id does not match with Aud/AZP. Throwing Authorizaion Exception");
                return ImmutablePair.of(HttpStatus.FORBIDDEN, null);
            }
            MosipUserDto mosipUserDto = buildMosipUser(decodedJWT, jwtToken);
            return ImmutablePair.of(HttpStatus.OK, mosipUserDto);
        }
        return ImmutablePair.of(HttpStatus.UNAUTHORIZED, null);
	}

    public ImmutablePair<HttpStatus, MosipUserDto> doOnlineTokenValidation(String jwtToken, WebClient webClient) {
        if ("".equals(issuerURI)) {
            LOGGER.warn("OIDC validate URL is not available in config file, not requesting for token validation.");
            return ImmutablePair.of(HttpStatus.EXPECTATION_FAILED, null);
        }

        DecodedJWT decodedJWT = JWT.decode(jwtToken);
        HttpHeaders headers = new HttpHeaders();
		headers.add(AuthAdapterConstant.AUTH_REQUEST_COOOKIE_HEADER, AuthAdapterConstant.BEARER_STR + jwtToken);
		String realm = getRealM(decodedJWT);
        String userInfoPath = issuerURI + realm + userInfo;
        ClientResponse response = webClient.method(HttpMethod.GET)
                                           .uri(userInfoPath)
                                           .headers(httpHeaders -> {
                                                httpHeaders.addAll(headers);
                                            })
                                           .exchange()
                                           .block();
        if (response.statusCode() == HttpStatus.OK) {
            ObjectNode responseBody = response.bodyToMono(ObjectNode.class).block();
            List<ServiceError> validationErrorsList = ExceptionUtils.getServiceErrorList(responseBody.asText());
            if (!validationErrorsList.isEmpty()) {
                LOGGER.error("Error in validate token. Code {}, message {}", validationErrorsList.get(0).getErrorCode(), 
                    validationErrorsList.get(0).getMessage());
                return ImmutablePair.of(HttpStatus.UNAUTHORIZED, null);
            }

            // validating audience | azp claims.
            boolean matchFound = validateAudience(decodedJWT);
            if (!matchFound) {
                LOGGER.error("Provided Client Id does not match with Aud/AZP. Throwing Authorizaion Exception");
                return ImmutablePair.of(HttpStatus.FORBIDDEN, null);
            }
            MosipUserDto mosipUserDto = buildMosipUser(decodedJWT, jwtToken);
            return ImmutablePair.of(HttpStatus.OK, mosipUserDto);
        }                
		LOGGER.error("user authentication failed for the provided token (WebClient).");
		return ImmutablePair.of(HttpStatus.UNAUTHORIZED, null);
    }
    
}