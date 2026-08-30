package com.craftlab.launcher.auth;

public record AuthSession(String username, String uuid, String accessToken, String userType) {
}
