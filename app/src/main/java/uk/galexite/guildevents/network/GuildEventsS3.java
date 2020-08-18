package uk.galexite.guildevents.network;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.SimpleTimeZone;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A group of static methods for accessing the data which is stored in the cloud (in Amazon S3).
 * Used by the UpdateDatabaseAsyncTask to load fresh data in to the database when the
 * EventListActivity is run.
 */
class GuildEventsS3 {
    private static final String TAG = "GuildEventsS3";

    private static final String BUCKET_REGION = "your-region-identifier";
    private static final String BUCKET_HOST = "your-s3-bucket.amazonaws.com";
    private static final String BUCKET_URL = "https://your-s3-bucket-url.amazonaws.com/";

    // As the hash for an empty payload (i.e. no upload) is the same in SHA256, we can store the
    // hash here and not worry about generating it.
    private static final String EMPTY_PAYLOAD_HASH
            = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final OkHttpClient okHttpClient = new OkHttpClient();

    /**
     * Generate the HTTP 'Authorization' header for connecting to S3.
     *
     * Yes, this is incredibly and annoyingly complicated... but that's the way Amazon has designed
     * authorisation to S3 buckets in AWS.
     * Based on https://czak.pl/2015/09/15/s3-rest-api-with-curl.html
     *
     * @param path the path of the object in the bucket
     * @param amzDate String representing the date the request was made
     * @param method the HTTP method used to make the request
     * @return the HTTP Authorization header
     */
    @SuppressWarnings("UnstableApiUsage")
    private static String getAuthorisationHeader(String path, String amzDate, String method) {
        // The list of headers that we will send to the S3 server as part of making the request.
        final String canonical = String.format(
                "%s\n/%s\n\nhost:%s\nx-amz-content-sha256:%s\nx-amz-date:%s\n\n" +
                        "host;x-amz-content-sha256;x-amz-date\n%s",
                method, path, BUCKET_HOST, EMPTY_PAYLOAD_HASH, amzDate, EMPTY_PAYLOAD_HASH);

        // HTTP is UTF-8 encoded, so we will encode all byte data in this charset.
        @SuppressWarnings("CharsetObjectCanBeUsed") // StandardCharsets cannot be used on API < 19
        final Charset utf8 = Charset.forName("UTF-8");

        final String messageDigest = Hashing.sha256()
                .hashString(canonical, utf8)
                .toString();

        // Get only the 'date' of the date-time string, i.e. 20200221 for 21/02/2020.
        final String date = amzDate.substring(0, 8);
        final String dateCredential = String.format("%s/%s/s3/aws4_request",
                date, BUCKET_REGION);

        // This is the data we need to sign for the server to accept our request as valid.
        final String toSign = String.format("AWS4-HMAC-SHA256\n%s\n%s\n%s",
                amzDate, dateCredential, messageDigest);

        // To generate the signing key is a four-step process. The key is generated by hashing the
        // date first, then the region with the date key, the service code with this region key,
        // and then the word 'aws4_request' is hashed with this service code key.

        final byte[] dateKey = Hashing.hmacSha256(("AWS4" + API_KEY).getBytes(utf8))
                .hashString(date, utf8)
                .asBytes();

        final byte[] dateRegionKey = Hashing.hmacSha256(dateKey)
                .hashString(BUCKET_REGION, utf8)
                .asBytes();

        final byte[] dateRegionServiceKey = Hashing.hmacSha256(dateRegionKey)
                .hashString("s3", utf8)
                .asBytes();

        final byte[] signingKey = Hashing.hmacSha256(dateRegionServiceKey)
                .hashString("aws4_request", utf8)
                .asBytes();

        // Use this signing key to generate the signature to sign the request with.

        final String signature = Hashing.hmacSha256(signingKey)
                .hashString(toSign, utf8)
                .toString();

        final String credential = String.format("%s/%s",
                ACCESS_KEY, dateCredential);

        return String.format(
                "AWS4-HMAC-SHA256 Credential=%s, " +
                        "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                        "Signature=%s", credential, signature);
    }

    /**
     * Get the current date and time as the x-amz-date format, required for signing requests.
     *
     * @return the String for the x-amz-date field
     */
    @NonNull
    private static String getAmzDate() {
        // Based on: https://github.com/aws/aws-sdk-java/blob/master/aws-java-sdk-core/
        //              src/test/java/com/amazonaws/auth/AWS4SignerTest.java
        final SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
                "yyyyMMdd'T'HHmmss'Z'", Locale.ENGLISH);
        dateTimeFormat.setTimeZone(new SimpleTimeZone(0, "UTC"));

        return dateTimeFormat.format(new Date());
    }

    /**
     * Send an HTTP HEAD request for the S3 object given by the path. A HEAD request is similar to
     * a GET request, but instead only returns metadata about the object.
     *
     * @param path to the item in the S3 bucket
     * @return HTTP headers detailing the object's metadata
     */
    private static Headers head(String path) {
        final String amzDate = getAmzDate();

        Request request = new Request.Builder()
                .url(BUCKET_URL + path)
                .head()
                .addHeader("Authorization",
                        getAuthorisationHeader(path, amzDate, "HEAD"))
                .addHeader("x-amz-content-sha256", EMPTY_PAYLOAD_HASH)
                .addHeader("x-amz-date", amzDate)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.headers();
            } else {
                final ResponseBody body = response.body();
                Log.e(TAG, String.format("head: request was not successful (status %d): %s",
                        response.code(), body == null ? "NO BODY!" : body.string()));
                Log.e(TAG, "head: further headers: " + response.headers());
            }
        } catch (IOException e) {
            Log.e(TAG, "head: IOException: " + e.getLocalizedMessage());
        }

        return null;
    }

    /**
     * Get an object from the S3 bucket given by the path.
     *
     * @param path to item in the S3 bucket
     * @return the file's contents
     */
    private static String get(String path) {
        final String amzDate = getAmzDate();

        Request request = new Request.Builder()
                .url(BUCKET_URL + path)
                .addHeader("Authorization",
                        getAuthorisationHeader(path, amzDate, "GET"))
                .addHeader("x-amz-content-sha256", EMPTY_PAYLOAD_HASH)
                .addHeader("x-amz-date", amzDate)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (response.isSuccessful()) {
                return body != null ? body.string() : null;
            } else {
                Log.e(TAG, String.format("get: request was not successful (status %d): %s",
                        response.code(), body == null ? "NO BODY!" : body.string()));
            }
        } catch (IOException e) {
            Log.e(TAG, "get: IOException: " + e.getLocalizedMessage());
        }

        return null;
    }

    /**
     * Get the date of when the object was last modified in the S3 bucket.
     *
     * @param path to item in the S3 bucket
     * @return the date when the object was last modified
     */
    private static Date getLastModified(String path) {
        final Headers headers = head(path);
        return headers != null ? headers.getDate("Last-Modified") : null;
    }

    /**
     * Get the JSON file containing the list of events.
     *
     * @return the list of events as a JSON string
     */
    static String getEventsJson() {
        return get("events.json");
    }

    /**
     * Get the date the events.json file was last modified in the S3 bucket.
     *
     * @return the date of last modification
     */
    static Date getEventsJsonLastModified() {
        return getLastModified("events.json");
    }

    /**
     * Get the JSON file containing the list of event organisers.
     *
     * @return the list of event organisers as a JSON string
     */
    static String getOrganisationsJson() {
        return get("organisations.json");
    }

    /**
     * Get the date the organisations.json file was last modified in the S3 bucket.
     *
     * @return the date of last modification
     */
    static Date getOrganisationsLastModified() {
        return getLastModified("organisations.json");
    }
}