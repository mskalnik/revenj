package org.revenj.storage;

import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Future;

public interface S3Repository {
	Future<InputStream> get(String bucket, String key);

	default Future<InputStream> get(S3 s3) {
		return get(s3.getBucket(), s3.getKey());
	}

	Future<S3> upload(
			String bucket,
			String key,
			InputStream stream,
			long length,
			String name,
			String mimeType,
			Map<String, String> metadata);

	Future<DeleteObjectResponse> delete(String bucket, String key);

	default Future<DeleteObjectResponse> delete(S3 s3) {
		return delete(s3.getBucket(), s3.getKey());
	}
}