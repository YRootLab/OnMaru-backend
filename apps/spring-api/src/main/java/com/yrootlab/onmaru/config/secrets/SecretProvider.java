package com.yrootlab.onmaru.config.secrets;

public interface SecretProvider {

    SecretBundle get(String name);
}
