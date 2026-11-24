package com.arnavjot.glimpse.network;

import androidx.annotation.Nullable;

import com.arnavjot.glimpse.pojo.EventBatchDto;
import com.arnavjot.glimpse.pojo.EventBatchResultDto;
import com.arnavjot.glimpse.pojo.FeedPageDto;
import com.arnavjot.glimpse.pojo.LikeDto;
import com.arnavjot.glimpse.pojo.RemoteConfigDto;
import com.arnavjot.glimpse.pojo.UploadDto;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** The Glimpse backend. Calls are made with execute() on the repository's executor. */
public interface ApiInterface {

    /** @param first a clip to put first on the first page (a shared link), or null */
    @GET("v1/feed")
    Call<FeedPageDto> feed(@Nullable @Query("cursor") String cursor, @Query("limit") int limit,
                           @Nullable @Query("first") String first);

    /**
     * This device's feature flags. Sends the last ETag: an unchanged config comes back
     * as 304 with no body.
     */
    @GET("v1/config")
    Call<RemoteConfigDto> config(@Nullable @Header("If-None-Match") String etag);

    /** Starts an upload: returns a pre-signed URL to PUT the file to (signed in only). */
    @POST("v1/uploads")
    Call<UploadDto.Ticket> createUpload(@Body UploadDto.NewUpload body);

    /** The PUT finished: the server checks the file and queues it for processing. */
    @POST("v1/uploads/{id}/complete")
    Call<UploadDto.Upload> completeUpload(@Path("id") String uploadId);

    /** Deletes one of this creator's uploads (and its clip from the feed). */
    @DELETE("v1/uploads/{id}")
    Call<Void> deleteUpload(@Path("id") String uploadId);

    /** This creator's uploads, newest first, with their processing status. */
    @GET("v1/uploads")
    Call<java.util.List<UploadDto.Upload>> myUploads();

    @POST("v1/events")
    Call<EventBatchResultDto> sendEvents(@Body EventBatchDto batch);

    @PUT("v1/videos/{id}/like")
    Call<LikeDto> like(@Path("id") String videoId, @Body LikeDto.Body body);

    @DELETE("v1/videos/{id}/like")
    Call<LikeDto> unlike(@Path("id") String videoId, @Query("device_id") String deviceId);
}
