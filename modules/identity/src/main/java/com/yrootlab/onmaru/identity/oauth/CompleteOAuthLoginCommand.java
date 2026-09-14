package com.yrootlab.onmaru.identity.oauth;

public record CompleteOAuthLoginCommand(
        OAuthProvider expectedProvider,
        String state,
        String browserNonce,
        String pkceVerifier,
        ExternalIdentity externalIdentity) {
}
