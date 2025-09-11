package org.revenj;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.*;

import org.revenj.storage.S3;
import org.revenj.storage.S3Repository;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class AmazonS3Repository implements S3Repository, Closeable {
	private final String bucketName;
	private final ExecutorService executorService;
	private final S3Client s3Client;
	private final boolean disposeExecutor;

	public AmazonS3Repository(Properties properties, Optional<ExecutorService> executorService) {
		bucketName = properties.getProperty("revenj.s3-bucket-name");
		String s3AccessKey = properties.getProperty("revenj.s3-user");
		String s3SecretKey = properties.getProperty("revenj.s3-secret");
		String s3Region = properties.getProperty("revenj.s3-region");
		disposeExecutor = !executorService.isPresent();
		this.executorService = executorService.orElse(Executors.newSingleThreadExecutor());
		if (s3AccessKey == null || s3AccessKey.isEmpty()) {
			throw new RuntimeException("S3 configuration is missing. Please add revenj.s3-user");
		}
		if (s3SecretKey == null || s3SecretKey.isEmpty()) {
			throw new RuntimeException("S3 configuration is missing. Please add revenj.s3-secret");
		}

		S3ClientBuilder builder = S3Client.builder()
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3AccessKey, s3SecretKey)));
		if (s3Region != null) {
			builder.region(Region.of(s3Region));
		}
		s3Client = builder.build();
	}

	private String getBucketName(final String name) throws IOException {
		String bn = name == null || name.isEmpty() ? bucketName : name;
		if (bn == null || bn.isEmpty()) {
			throw new IOException("Bucket name not specified for this S3 instance or system wide.\n"
					+ "Either specify revenj.s3-bucket-name in Properties as system wide name or provide a bucket name to this S3 instance");
		}
		return bn;
	}

	@Override
	public Future<InputStream> get(final String bucket, final String key) {
		return executorService.submit(() ->
			s3Client.getObject(GetObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build())
		);
	}

	@Override
	public Future<S3> upload(
			String bucket,
			String key,
			InputStream stream,
			long length,
			String name,
			String mimeType,
			Map<String, String> metadata) {
		return executorService.submit(() -> {
			String bn = getBucketName(bucket);
			PutObjectRequest.Builder builder = PutObjectRequest.builder()
					.bucket(bn)
					.key(key)
					.contentLength(length);
			if (metadata != null && !metadata.isEmpty()) {
				builder.metadata(metadata);
			}
			if (mimeType != null && !mimeType.isEmpty()) {
				builder.contentType(mimeType);
			}
			s3Client.putObject(builder.build(), RequestBody.fromInputStream(stream, length));
			return new S3(bn, key, length, name, mimeType, metadata);
		});
	}

	@Override
	public Future<DeleteObjectResponse> delete(String bucket, String key) {
		return executorService.submit(() -> {
			DeleteObjectRequest request = DeleteObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build();
			return s3Client.deleteObject(request);
		});
	}

	@Override
	public void close() throws IOException {
		if (disposeExecutor) {
			executorService.shutdown();
		}
	}
}
