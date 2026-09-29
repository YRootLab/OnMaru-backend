package com.yrootlab.onmaru.admin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("onmaru.admin.bootstrap")
public class AdminBootstrapProperties {
    private String email;
    private String password;
    private String nickname = "운영 관리자";

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public boolean configured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
}
