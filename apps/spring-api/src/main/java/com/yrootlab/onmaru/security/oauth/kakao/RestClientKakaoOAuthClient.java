package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.identity.oauth.ExternalIdentity;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

final class RestClientKakaoOAuthClient implements KakaoOAuthClient {

    private static final String ISSUER = "https://kauth.kakao.com";

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;
    private final String clientSecret;

    RestClientKakaoOAuthClient(RestClient restClient, KakaoOAuthProperties properties, String clientSecret) {
        this.restClient = restClient;
        this.properties = properties;
        this.clientSecret = clientSecret;
    }

    @Override
    public ExternalIdentity authenticate(String code, String codeVerifier) {
        var token = exchangeCode(code, codeVerifier);
        var user = fetchUser(token.accessToken());
        if (user.id() == null) {
            throw new IllegalStateException("Kakao user id is missing");
        }
        return new ExternalIdentity("KAKAO", ISSUER, String.valueOf(user.id()));
    }

    private KakaoTokenResponse exchangeCode(String code, String codeVerifier) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.getClientId());
        form.add("redirect_uri", properties.getRedirectUri());
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_secret", clientSecret);

        var response = restClient.post()
                .uri(properties.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KakaoTokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("Kakao access token response is invalid");
        }
        return response;
    }

    private KakaoUserResponse fetchUser(String accessToken) {
        var response = restClient.get()
                .uri(properties.getUserInfoUri())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(KakaoUserResponse.class);
        if (response == null) {
            throw new IllegalStateException("Kakao user response is invalid");
        }
        return response;
    }

    private record KakaoTokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken) {
    }

    private record KakaoUserResponse(Long id) {
    }
}
