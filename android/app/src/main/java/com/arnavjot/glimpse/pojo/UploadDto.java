package com.arnavjot.glimpse.pojo;

import com.google.gson.annotations.SerializedName;

/** /v1/uploads request and response bodies. */
public final class UploadDto {

    private UploadDto() {
    }

    public static final String PENDING = "pending_upload";
    public static final String UPLOADED = "uploaded";
    public static final String PROCESSING = "processing";
    public static final String READY = "ready";
    public static final String FAILED = "failed";

    /** One upload as the server tracks it. */
    public static final class Upload {
        @SerializedName("id") public String id;
        @SerializedName("title") public String title;
        @SerializedName("status") public String status;
        @SerializedName("size_bytes") public long sizeBytes;
        @SerializedName("error") public String error;
        @SerializedName("video_id") public String videoId;
        @SerializedName("created_at") public String createdAt;
    }

    /** Where to PUT the file (straight to storage), and until when. */
    public static final class Ticket {
        @SerializedName("upload") public Upload upload;
        @SerializedName("upload_url") public String uploadUrl;
        @SerializedName("content_type") public String contentType;
        @SerializedName("expires_at") public String expiresAt;
    }

    public static final class NewUpload {
        @SerializedName("title") public final String title;
        @SerializedName("content_type") public final String contentType;
        @SerializedName("size_bytes") public final long sizeBytes;

        public NewUpload(String title, String contentType, long sizeBytes) {
            this.title = title;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
        }
    }
}
