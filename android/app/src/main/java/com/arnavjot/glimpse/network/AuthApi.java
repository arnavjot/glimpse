package com.arnavjot.glimpse.network;

import com.arnavjot.glimpse.pojo.AuthDto;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

/**
 * Sign-in endpoints. Served by a client without the auth interceptor, so refreshing a
 * token can never trigger another refresh, and without body logging (passwords).
 */
public interface AuthApi {

    @POST("v1/auth/register")
    Call<AuthDto.Tokens> register(@Body AuthDto.Register body);

    @POST("v1/auth/login")
    Call<AuthDto.Tokens> login(@Body AuthDto.Login body);

    @POST("v1/auth/refresh")
    Call<AuthDto.Tokens> refresh(@Body AuthDto.Refresh body);

    @POST("v1/auth/logout")
    Call<Void> logout(@Body AuthDto.Refresh body);
}
