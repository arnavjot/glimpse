package com.arnavjot.glimpse.pojo;

import com.google.gson.annotations.SerializedName;

/** Request and response bodies of /v1/auth/... */
public final class AuthDto {

    private AuthDto() {
    }

    /** What every successful sign-in, sign-up or refresh returns. */
    public static final class Tokens {
        @SerializedName("access_token") public String accessToken;
        @SerializedName("refresh_token") public String refreshToken;
        /** Seconds until the access token expires. */
        @SerializedName("expires_in") public long expiresIn;
        @SerializedName("user") public User user;
    }

    public static final class User {
        @SerializedName("id") public String id;
        @SerializedName("handle") public String handle;
    }

    public static final class Register {
        @SerializedName("email") public final String email;
        @SerializedName("password") public final String password;
        @SerializedName("handle") public final String handle;

        public Register(String email, String password, String handle) {
            this.email = email;
            this.password = password;
            this.handle = handle;
        }
    }

    public static final class Login {
        @SerializedName("email") public final String email;
        @SerializedName("password") public final String password;

        public Login(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static final class Refresh {
        @SerializedName("refresh_token") public final String refreshToken;

        public Refresh(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    /** Error body: {"detail": "..."} */
    public static final class Error {
        @SerializedName("detail") public String detail;
    }
}
