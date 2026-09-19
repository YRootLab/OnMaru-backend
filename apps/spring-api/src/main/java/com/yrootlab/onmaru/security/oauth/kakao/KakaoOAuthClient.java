package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.identity.oauth.ExternalIdentity;

public interface KakaoOAuthClient {

    ExternalIdentity authenticate(String code, String codeVerifier);
}
